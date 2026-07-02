package com.finbank.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.dto.TransferRequest;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.IdempotencyKeyRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    private static final String EMAIL = "idempotency@test.com";
    private static final String PASSWORD = "1234";

    private String jwtToken;
    private Account from;
    private Account to;

    @BeforeEach
    void setUp() throws Exception {
        // DB 초기화 (의존성 순서 중요 — members를 참조하는 테이블을 먼저 정리)
        idempotencyKeyRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        transactionLogRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();

        // 회원가입
        String registerBody = objectMapper.writeValueAsString(
                Map.of("email", EMAIL, "name", "테스터", "password", PASSWORD)
        );
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody));

        // 로그인 → JWT 발급
        String loginBody = objectMapper.writeValueAsString(
                Map.of("email", EMAIL, "password", PASSWORD)
        );
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> loginResponse = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), Map.class);
        jwtToken = (String) loginResponse.get("accessToken");

        // 계좌 생성 (DB 직접)
        Member member = memberRepository.findByEmail(EMAIL).orElseThrow();
        from = accountRepository.saveAndFlush(new Account(member, "111111111111", 100_000L));
        to = accountRepository.saveAndFlush(new Account(member, "222222222222", 0L));
    }

    @Test
    @DisplayName("동일한 Idempotency-Key로 이체 2번 시도 → 이체는 1번만 실행")
    void same_idempotency_key_should_transfer_only_once() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = objectMapper.writeValueAsString(
                new TransferRequest(from.getId(), to.getAccountNumber(), 30_000L)
        );

        // 첫 번째 요청 - 정상 처리
        mockMvc.perform(post("/api/accounts/transfer")
                        .header("Authorization", "Bearer " + jwtToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        // 두 번째 요청 - 동일한 키 → 캐시된 응답 반환 (이체 미실행)
        mockMvc.perform(post("/api/accounts/transfer")
                        .header("Authorization", "Bearer " + jwtToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        // 잔액 검증 - 30,000원 이체가 딱 1번만 실행됐어야 함
        Account finalFrom = accountRepository.findById(from.getId()).orElseThrow();
        Account finalTo = accountRepository.findById(to.getId()).orElseThrow();

        assertThat(finalFrom.getBalance()).isEqualTo(70_000L);
        assertThat(finalTo.getBalance()).isEqualTo(30_000L);

        // 거래 로그도 1건만
        assertThat(transactionLogRepository.findAll())
                .filteredOn(l -> l.getType() == TransactionType.TRANSFER_OUT)
                .hasSize(1);
    }

    @Test
    @DisplayName("다른 Idempotency-Key로 이체 2번 → 이체 2번 모두 실행")
    void different_idempotency_keys_should_transfer_twice() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new TransferRequest(from.getId(), to.getAccountNumber(), 30_000L)
        );

        // 서로 다른 키로 2번 요청
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/accounts/transfer")
                            .header("Authorization", "Bearer " + jwtToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk());
        }

        // 잔액 검증 - 두 번 이체됨
        Account finalFrom = accountRepository.findById(from.getId()).orElseThrow();
        assertThat(finalFrom.getBalance()).isEqualTo(40_000L);

        assertThat(transactionLogRepository.findAll())
                .filteredOn(l -> l.getType() == TransactionType.TRANSFER_OUT)
                .hasSize(2);
    }

    @Test
    @DisplayName("Idempotency-Key 없이 이체 → 일반 처리 (멱등성 미적용)")
    void no_idempotency_key_should_pass_through_normally() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                new TransferRequest(from.getId(), to.getAccountNumber(), 30_000L)
        );

        // 헤더 없이 요청
        mockMvc.perform(post("/api/accounts/transfer")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        Account finalFrom = accountRepository.findById(from.getId()).orElseThrow();
        assertThat(finalFrom.getBalance()).isEqualTo(70_000L);
    }
}
