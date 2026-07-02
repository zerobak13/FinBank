package com.finbank.backend.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbank.backend.domain.IdempotencyKey;
import com.finbank.backend.domain.Member;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.repository.IdempotencyKeyRepository;
import com.finbank.backend.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Aspect
@Component
public class IdempotencyAspect {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyKeyRepository idempotencyKeyRepository,
                              MemberRepository memberRepository,
                              ObjectMapper objectMapper) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.finbank.backend.idempotency.Idempotent)")
    public Object checkIdempotency(ProceedingJoinPoint joinPoint) throws Throwable {

        // 1. HTTP 요청에서 Idempotency-Key 헤더 추출
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                        .getRequest();

        String idempotencyKeyHeader = request.getHeader("Idempotency-Key");

        // 헤더 없으면 멱등성 체크 없이 그냥 통과
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            return joinPoint.proceed();
        }

        // 2. 현재 로그인 회원 조회
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Member not found: " + email));

        // 3. 기존 키 조회
        Optional<IdempotencyKey> existing =
                idempotencyKeyRepository.findByIdempotencyKeyAndMemberId(
                        idempotencyKeyHeader, member.getId());

        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();

            if (record.isExpired()) {
                // 만료된 키는 삭제 후 새 요청으로 처리
                idempotencyKeyRepository.delete(record);
            } else if (record.isCompleted()) {
                // 완료된 기록 → 캐시된 응답 그대로 반환 (DB 실행 없음)
                Object body = objectMapper.readValue(record.getResponseBody(), Object.class);
                return ResponseEntity.status(record.getResponseStatus()).body(body);
            } else {
                // 처리 중 상태 (아직 응답 저장 전) → 동시 중복 요청으로 판단
                throw new BusinessException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도하세요.");
            }
        }

        // 4. 새 키 선점 INSERT (처리 중 상태)
        //    UNIQUE 제약으로 동시 중복 요청 중 하나만 통과, 나머지는 예외
        IdempotencyKey newRecord = IdempotencyKey.of(
                idempotencyKeyHeader, member, request.getRequestURI());
        try {
            idempotencyKeyRepository.saveAndFlush(newRecord);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도하세요.");
        }

        // 5. 실제 비즈니스 로직 실행
        try {
            Object result = joinPoint.proceed();

            // 6. 성공 → 응답 저장
            if (result instanceof ResponseEntity<?> responseEntity) {
                String responseBody = objectMapper.writeValueAsString(responseEntity.getBody());
                newRecord.complete(responseEntity.getStatusCode().value(), responseBody);
                idempotencyKeyRepository.save(newRecord);
            }

            return result;

        } catch (Throwable e) {
            // 7. 실패 → 키 삭제 (클라이언트가 수정 후 재시도할 수 있도록)
            idempotencyKeyRepository.delete(newRecord);
            throw e;
        }
    }
}
