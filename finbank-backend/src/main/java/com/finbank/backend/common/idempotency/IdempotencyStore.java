package com.finbank.backend.common.idempotency;

import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

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
 *
 * <p><b>오래된 선점은 넘겨받는다.</b> 선점은 즉시 커밋되고 해제는 본 처리가 예외를 던질 때만 하므로, 본 처리 도중
 * 프로세스가 죽으면(배포·OOM·재시작) 키가 IN_PROGRESS로 남는다. 본 처리 트랜잭션은 롤백돼 돈은 움직이지 않았는데,
 * 같은 키의 재시도는 영원히 409를 받는다(화면은 409를 "아직 미확정"으로 보고 같은 키를 계속 보낸다).
 * 그래서 선점한 지 {@code app.idempotency.stale-after-seconds}가 지난 IN_PROGRESS는 같은 내용의 재시도가 넘겨받는다.</p>
 *
 * <p>넘겨받기가 안전한 이유 — 원래 요청이 사실은 살아 있었더라도 <b>돈은 한 번만 움직인다</b>. 돈의 이동은 확정
 * ({@link IdempotencyRecordRepository#completeIfInProgress}, 조건부 UPDATE)과 같은 커밋이고, 같은 키로 확정에 성공하는
 * 본 처리는 하나뿐이다. 늦은 쪽은 확정이 0행이라 예외로 롤백된다. 기준 시간은 정확성이 아니라 "죽은 요청을 얼마나 빨리
 * 포기하느냐"만 정한다 — 너무 짧으면 느린 정상 요청이 롤백될 뿐이다.</p>
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;
    private final Duration staleAfter;

    public IdempotencyStore(IdempotencyRecordRepository repository,
                            @Value("${app.idempotency.stale-after-seconds:120}") long staleAfterSeconds) {
        this.repository = repository;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
    }

    /**
     * 선점 결과. {@code replay}가 있으면 이미 완료된 요청의 응답 스냅샷(JSON) — 그대로 돌려주면 된다.
     * 없으면 내가 처리 주체이고, {@code recordId}는 실패 시 해제할 내 선점 행이다.
     */
    public record Claim(Long recordId, String replay) {
        public boolean isReplay() {
            return replay != null;
        }
    }

    /**
     * 키 선점 시도.
     *
     * @throws DataIntegrityViolationException 동시 요청이 UNIQUE 제약으로 밀어냈을 때
     *                                         (호출자가 {@link #findCompletedResponse}로 재판정)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String idemKey, Long memberId, String requestHash) {
        IdempotencyRecord existing = repository.findByIdemKey(idemKey).orElse(null);
        if (existing != null) {
            if (!isStale(existing, requestHash)) {
                return new Claim(null, replayOrReject(existing, requestHash));
            }
            // 오래된 선점 회수 — 0행이면 그 사이 원래 요청이 확정했거나 다른 재시도가 먼저 넘겨받았다
            if (repository.deleteStaleInProgress(existing.getId(), staleBefore()) == 0) {
                throw inProgress();
            }
        }
        // UNIQUE 제약 위반은 여기서 잡지 않는다. 예외를 잡아 같은 트랜잭션에서 재조회하면
        // 이미 rollback-only로 마킹된 트랜잭션이라 커밋에 실패한다.
        // 따라서 "새 트랜잭션에서 다시 읽기"를 호출자에게 맡긴다.
        IdempotencyRecord claimed = repository.saveAndFlush(IdempotencyRecord.claim(idemKey, memberId, requestHash));
        return new Claim(claimed.getId(), null);
    }

    /**
     * 선점 경쟁에서 밀린 요청의 재판정 — 승자가 이미 끝냈으면 그 응답을, 아직 처리 중이면 409.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String findCompletedResponse(String idemKey, String requestHash) {
        IdempotencyRecord record = repository.findByIdemKey(idemKey).orElseThrow(IdempotencyStore::inProgress);
        return replayOrReject(record, requestHash);
    }

    /**
     * 응답 스냅샷 확정. 본 처리 트랜잭션 안에서만 호출된다({@code MANDATORY}).
     *
     * <p>엔티티를 읽어 고치지 않고 조건부 UPDATE로 한다. 읽은 엔티티는 트랜잭션 첫 조회 시점의 스냅샷이라, 그 사이
     * 다른 본 처리가 같은 키를 확정했어도 IN_PROGRESS로 보인다. 0행이면 이 본 처리는 커밋하면 안 된다 — 예외로
     * 돈의 이동까지 롤백한다.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String idemKey, String responseBody) {
        if (repository.completeIfInProgress(idemKey, responseBody, LocalDateTime.now()) == 0) {
            throw new IllegalStateException("확정할 수 있는 선점이 없습니다 (선점되지 않았거나 이미 확정된 키): " + idemKey);
        }
    }

    /** 본 처리 실패 시 내 선점 해제 — 재시도를 허용한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long recordId) {
        repository.deleteInProgressById(recordId);
    }

    /** 넘겨받을 수 있는 선점인가 — 같은 내용(해시 일치)이고, 아직 처리 중이며, 기준 시간보다 오래됐다 */
    private boolean isStale(IdempotencyRecord record, String requestHash) {
        return record.matches(requestHash)
                && !record.isCompleted()
                && record.getCreatedAt().isBefore(staleBefore());
    }

    private LocalDateTime staleBefore() {
        return LocalDateTime.now().minus(staleAfter);
    }

    private String replayOrReject(IdempotencyRecord record, String requestHash) {
        if (!record.matches(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                    "이미 사용된 멱등키로 다른 내용의 요청을 보낼 수 없습니다.");
        }
        if (!record.isCompleted()) {
            throw inProgress();
        }
        return record.getResponseBody();
    }

    private static BusinessException inProgress() {
        return new BusinessException(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
                "동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
    }
}
