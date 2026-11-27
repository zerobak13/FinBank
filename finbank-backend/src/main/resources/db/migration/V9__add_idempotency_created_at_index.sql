-- 멱등키 생성 시각 인덱스.
--
-- 보관 기간이 지난 키를 매일 지우는 정리 작업(IdempotencyCleanupScheduler)이
-- `WHERE created_at < ?`로 지운다. 인덱스가 없으면 매번 테이블 전체를 훑고,
-- 훑는 동안 잠근 범위 때문에 그 시각의 선점 INSERT가 기다릴 수 있다.
CREATE INDEX idx_idem_created_at ON idempotency_keys (created_at);
