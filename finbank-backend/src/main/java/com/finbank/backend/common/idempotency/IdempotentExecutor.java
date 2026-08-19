package com.finbank.backend.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbank.backend.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 멱등 실행기 — "같은 키의 요청은 딱 한 번만 실제로 수행되고, 그 뒤로는 같은 응답을 돌려준다".
 *
 * <p><b>이 클래스는 트랜잭션이 없다(의도적).</b> 선점/해제는 각각 별도 트랜잭션이어야 하고,
 * 본 처리(action)는 자기 트랜잭션을 가진 다른 빈의 메서드다. 실행기가 트랜잭션을 열면
 * 세 경계가 하나로 합쳐져 선점의 의미가 사라진다.</p>
 *
 * <pre>
 * claim(REQUIRES_NEW, 즉시 커밋)
 *   ├─ 이미 COMPLETED → 저장된 응답 반환 (본 처리 없음)
 *   ├─ 아직 IN_PROGRESS → 409
 *   └─ 선점 성공 → action 실행 (자체 트랜잭션 + 그 안에서 complete)
 *                     ├─ 성공 → 커밋 (돈의 이동 + COMPLETED가 한 커밋)
 *                     └─ 실패 → 롤백 후 release(REQUIRES_NEW)로 키 삭제 = 재시도 허용
 * </pre>
 */
@Component
public class IdempotentExecutor {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * @param idemKey     클라이언트가 보낸 멱등키
     * @param memberId    요청자
     * @param requestHash 요청 내용 해시 (같은 키 + 다른 내용 → 422)
     * @param responseType 응답 타입 (재시도 시 저장된 JSON을 이 타입으로 복원)
     * @param action      본 처리 — 자기 트랜잭션 안에서 {@link IdempotencyStore#complete}를 호출해야 한다
     */
    public <T> T run(String idemKey, Long memberId, String requestHash,
                     Class<T> responseType, Supplier<T> action) {

        validateKey(idemKey);

        Optional<String> replay;
        try {
            replay = store.claim(idemKey, memberId, requestHash);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 충돌 = 동시 요청이 먼저 선점. 새 트랜잭션에서 승자의 상태를 다시 읽는다.
            return deserialize(store.findCompletedResponse(idemKey, requestHash), responseType);
        }

        if (replay.isPresent()) {
            return deserialize(replay.get(), responseType);
        }

        try {
            return action.get();
        } catch (RuntimeException e) {
            store.release(idemKey); // 실패한 요청은 같은 키로 재시도할 수 있어야 한다
            throw e;
        }
    }

    /** 본 처리 트랜잭션 안에서 응답 스냅샷을 확정한다. */
    public void complete(String idemKey, Object response) {
        store.complete(idemKey, serialize(response));
    }

    /** 키는 DB 컬럼 길이(64)를 넘을 수 없다 — 잘린 키가 다른 요청과 충돌하는 사고를 미리 막는다. */
    private void validateKey(String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new BusinessException("Idempotency-Key 헤더가 필요합니다.");
        }
        if (idemKey.length() > 64) {
            throw new BusinessException("Idempotency-Key는 64자 이하여야 합니다.");
        }
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 응답 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 응답 역직렬화 실패", e);
        }
    }
}
