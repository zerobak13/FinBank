package com.finbank.backend.common.idempotency;

import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 멱등키 저장소 — 선점 / 확정 / 해제의 세 연산만 제공한다.
 *
 * <p><b>이 클래스의 핵심은 트랜잭션 경계다.</b></p>
 *
 * <ul>
 *   <li><b>선점(claim)은 {@code REQUIRES_NEW}</b> — 본 처리와 별도 트랜잭션으로 즉시 커밋한다.
 *       같은 트랜잭션에 두면 본 처리가 롤백될 때 키도 함께 사라져, 이미 진행 중인 요청을
 *       동시 재시도가 그대로 통과해버린다(= 멱등이 무너진다).</li>
 *   <li><b>확정(complete)은 {@code MANDATORY}</b> — 반드시 본 처리 트랜잭션 안에서 호출된다.
 *       "돈이 움직인 사실"과 "응답 스냅샷"이 같은 커밋에 묶여야 한다. 별도 트랜잭션이면
 *       본 처리 커밋 직후 장애가 났을 때 COMPLETED가 남지 않아 이중 처리 위험이 생긴다.</li>
 *   <li><b>해제(release)는 {@code REQUIRES_NEW}</b> — 본 처리가 롤백된 뒤에 호출되므로
 *       롤백되는 트랜잭션에 얹으면 삭제도 함께 사라진다.</li>
 * </ul>
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

    public IdempotencyStore(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 키 선점 시도.
     *
     * @return 비어 있으면 "내가 처리 주체" (본 처리 진행),
     * 값이 있으면 이미 완료된 요청의 응답 스냅샷(JSON) — 그대로 반환하면 된다.
     * @throws DataIntegrityViolationException 동시 요청이 UNIQUE 제약으로 밀어냈을 때
     *                                         (호출자가 {@link #findCompletedResponse}로 재판정)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> claim(String idemKey, Long memberId, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findByIdemKey(idemKey);
        if (existing.isPresent()) {
            return Optional.of(replayOrReject(existing.get(), requestHash));
        }
        // UNIQUE 제약 위반은 여기서 잡지 않는다. 예외를 잡아 같은 트랜잭션에서 재조회하면
        // 이미 rollback-only로 마킹된 트랜잭션이라 커밋에 실패한다.
        // 따라서 "새 트랜잭션에서 다시 읽기"를 호출자에게 맡긴다.
        repository.saveAndFlush(IdempotencyRecord.claim(idemKey, memberId, requestHash));
        return Optional.empty();
    }

    /**
     * 선점 경쟁에서 밀린 요청의 재판정 — 승자가 이미 끝냈으면 그 응답을, 아직 처리 중이면 409.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String findCompletedResponse(String idemKey, String requestHash) {
        IdempotencyRecord record = repository.findByIdemKey(idemKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
                        "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."));
        return replayOrReject(record, requestHash);
    }

    /**
     * 응답 스냅샷 확정. 본 처리 트랜잭션 안에서만 호출된다({@code MANDATORY}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String idemKey, String responseBody) {
        IdempotencyRecord record = repository.findByIdemKey(idemKey)
                .orElseThrow(() -> new IllegalStateException("선점되지 않은 멱등키입니다: " + idemKey));
        record.complete(responseBody);
    }

    /** 본 처리 실패 시 선점 해제 — 재시도를 허용한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String idemKey) {
        repository.deleteInProgress(idemKey);
    }

    private String replayOrReject(IdempotencyRecord record, String requestHash) {
        if (!record.matches(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                    "이미 사용된 멱등키로 다른 내용의 요청을 보낼 수 없습니다.");
        }
        if (!record.isCompleted()) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
                    "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
        }
        return record.getResponseBody();
    }
}
