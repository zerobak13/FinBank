package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.dto.CashResponse;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.account.repository.TransactionLogRepository;
import com.finbank.backend.common.idempotency.IdempotencyCleanupScheduler;
import com.finbank.backend.common.idempotency.IdempotencyRecordRepository;
import com.finbank.backend.common.idempotency.IdempotencyStatus;
import com.finbank.backend.common.idempotency.IdempotencyStore;
import com.finbank.backend.common.idempotency.RequestHash;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 선점한 채 죽은 요청 — IN_PROGRESS로 남은 멱등키.
 *
 * <p>선점은 즉시 커밋되고 해제는 본 처리가 예외를 던질 때만 한다. 본 처리 도중 서버가 죽으면 키가 IN_PROGRESS로 남아
 * 같은 키의 재시도가 영원히 409를 받았다. 이제 기준 시간이 지난 선점은 같은 내용의 재시도가 넘겨받고,
 * 원래 요청이 사실은 살아 있었더라도 돈은 한 번만 움직인다(확정은 같은 키에 한 번만 성공한다).</p>
 */
@SpringBootTest
class StaleIdempotencyClaimTest {

    @Autowired AccountMoneyService accountMoneyService;
    @Autowired AccountService accountService;
    @Autowired IdempotencyStore idempotencyStore;
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired IdempotencyCleanupScheduler cleanupScheduler;
    @Autowired AccountRepository accountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "stale-idem@test.com";
    private static final BigDecimal INITIAL = new BigDecimal("1000000");
    private static final BigDecimal AMOUNT = new BigDecimal("70000");

    private Member member;
    private Account account;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        member = memberRepository.saveAndFlush(new Member(EMAIL, "멱등테스터", "password"));
        account = accountRepository.saveAndFlush(new Account(member, TestAccountNumbers.next(), INITIAL));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("선점한 지 오래된 IN_PROGRESS 키는 같은 내용의 재시도가 넘겨받아 처리한다")
    void staleClaim_isTakenOverByRetry() {
        String key = UUID.randomUUID().toString();
        idempotencyStore.claim(key, member.getId(), depositHash(AMOUNT));   // 선점한 채 서버가 죽었다
        ageClaim(key, 10);

        CashResponse res = accountMoneyService.deposit(account.getId(), AMOUNT, key);

        assertThat(res.balanceAfter()).isEqualByComparingTo(INITIAL.add(AMOUNT));
        assertThat(balance()).isEqualByComparingTo(INITIAL.add(AMOUNT));
        assertThat(countOf(TransactionType.DEPOSIT)).isEqualTo(1);
        assertThat(idempotencyRecordRepository.findByIdemKey(key).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("선점한 지 얼마 안 된 IN_PROGRESS 키는 여전히 409 — 살아 있는 요청을 가로채지 않는다")
    void freshClaim_isStillInProgress() {
        String key = UUID.randomUUID().toString();
        idempotencyStore.claim(key, member.getId(), depositHash(AMOUNT));

        assertThatThrownBy(() -> accountMoneyService.deposit(account.getId(), AMOUNT, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS);
        assertThat(balance()).isEqualByComparingTo(INITIAL);
    }

    @Test
    @DisplayName("오래된 선점이라도 내용이 다른 요청은 넘겨받지 못한다 (422)")
    void staleClaim_withDifferentContent_isRejected() {
        String key = UUID.randomUUID().toString();
        idempotencyStore.claim(key, member.getId(), depositHash(AMOUNT));
        ageClaim(key, 10);

        assertThatThrownBy(() -> accountMoneyService.deposit(account.getId(), new BigDecimal("1"), key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISMATCH);
        assertThat(balance()).isEqualByComparingTo(INITIAL);
    }

    @Test
    @DisplayName("넘겨받힌 원래 요청이 뒤늦게 끝나려 하면 확정에 실패해 롤백된다 — 돈은 한 번만 움직인다")
    void lateOriginal_afterTakeover_isRolledBack() {
        String key = UUID.randomUUID().toString();
        idempotencyStore.claim(key, member.getId(), depositHash(AMOUNT));   // 원래 요청의 선점 (아주 느리다)
        ageClaim(key, 10);
        accountMoneyService.deposit(account.getId(), AMOUNT, key);          // 재시도가 넘겨받아 끝냈다

        // 원래 요청의 본체가 이제야 확정하려 한다
        assertThatThrownBy(() -> accountService.deposit(account.getId(), AMOUNT, key))
                .isInstanceOf(IllegalStateException.class);

        assertThat(balance()).isEqualByComparingTo(INITIAL.add(AMOUNT));
        assertThat(countOf(TransactionType.DEPOSIT)).isEqualTo(1);
    }

    @Test
    @DisplayName("넘겨받힌 원래 요청의 해제는 새 선점을 지우지 않는다")
    void releaseOfTakenOverClaim_keepsNewClaim() {
        String key = UUID.randomUUID().toString();
        IdempotencyStore.Claim original = idempotencyStore.claim(key, member.getId(), depositHash(AMOUNT));
        ageClaim(key, 10);
        IdempotencyStore.Claim takeover = idempotencyStore.claim(key, member.getId(), depositHash(AMOUNT));

        idempotencyStore.release(original.recordId());   // 원래 요청이 뒤늦게 실패했다

        assertThat(takeover.recordId()).isNotEqualTo(original.recordId());
        assertThat(idempotencyRecordRepository.findByIdemKey(key).orElseThrow().getId())
                .isEqualTo(takeover.recordId());
    }

    @Test
    @DisplayName("정리 작업은 보관 기간이 지난 키만 지운다 (상태와 관계없이)")
    void cleanup_deletesOnlyExpiredKeys() {
        String oldCompleted = UUID.randomUUID().toString();
        String oldInProgress = UUID.randomUUID().toString();
        String recent = UUID.randomUUID().toString();
        accountMoneyService.deposit(account.getId(), AMOUNT, oldCompleted);
        idempotencyStore.claim(oldInProgress, member.getId(), depositHash(AMOUNT));
        accountMoneyService.deposit(account.getId(), AMOUNT, recent);
        ageClaim(oldCompleted, 60 * 24 * 8);
        ageClaim(oldInProgress, 60 * 24 * 8);

        int deleted = cleanupScheduler.purgeCreatedBefore(LocalDateTime.now().minusDays(7));

        assertThat(deleted).isEqualTo(2);
        assertThat(idempotencyRecordRepository.findByIdemKey(oldCompleted)).isEmpty();
        assertThat(idempotencyRecordRepository.findByIdemKey(oldInProgress)).isEmpty();
        assertThat(idempotencyRecordRepository.findByIdemKey(recent)).isPresent();
    }

    private String depositHash(BigDecimal amount) {
        return RequestHash.of("account-deposit", member.getId(), account.getId(),
                amount.stripTrailingZeros().toPlainString());
    }

    /** 선점 시각을 과거로 돌린다 — 그만큼 전에 선점하고 서버가 죽은 것과 같다 */
    private void ageClaim(String key, long minutes) {
        jdbcTemplate.update("update idempotency_keys set created_at = ? where idem_key = ?",
                LocalDateTime.now().minusMinutes(minutes), key);
    }

    private BigDecimal balance() {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private long countOf(TransactionType type) {
        return transactionLogRepository.findAll().stream().filter(t -> t.getType() == type).count();
    }
}
