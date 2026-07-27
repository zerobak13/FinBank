-- =====================================================
-- V5: 멱등키 저장소 + 중도상환수수료율 스냅샷
--
-- [왜 멱등키인가]
-- 상환 버튼을 두 번 누르거나 네트워크 타임아웃으로 클라이언트가 재시도하면
-- "같은 의도의 요청"이 서버에 두 번 도착한다. 돈이 두 번 빠지면 안 되므로
-- 클라이언트가 요청마다 고유 키(UUID)를 보내고, 서버는 같은 키의 두 번째 요청에
-- 저장해둔 응답을 그대로 돌려준다(토스·Stripe 등 금융 API의 필수 패턴).
--
-- [설계 포인트]
-- 1. idem_key UNIQUE — 선점(INSERT)의 성공/실패가 곧 "내가 처리 주체인가"의 판정이다.
--    애플리케이션의 if문이 아니라 DB 제약이 경쟁을 중재하므로 다중 인스턴스에서도 성립한다.
-- 2. request_hash — 같은 키로 다른 내용을 보내는 것은 클라이언트 버그다. 해시가 다르면 422.
-- 3. response_body — 재시도에 "같은 응답"을 돌려주기 위한 스냅샷.
-- 4. status(IN_PROGRESS/COMPLETED) — 처리 중인 키에 대한 동시 요청은 409로 막는다.
-- =====================================================

CREATE TABLE idempotency_keys
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    idem_key      VARCHAR(64) NOT NULL COMMENT '클라이언트가 보낸 Idempotency-Key (UUID)',
    member_id     BIGINT      NOT NULL COMMENT '요청자 (감사 추적용)',
    -- SHA-256 hex는 항상 64자라 CHAR(64)가 자연스럽지만, ddl-auto=validate가
    -- CHAR와 VARCHAR를 다른 타입으로 판정하므로 엔티티(String, length=64)와 맞춰 VARCHAR로 둔다.
    request_hash  VARCHAR(64) NOT NULL COMMENT '요청 내용의 SHA-256 — 같은 키 + 다른 내용 탐지',
    status        VARCHAR(20) NOT NULL COMMENT 'IN_PROGRESS(선점) | COMPLETED(응답 확정)',
    response_body VARCHAR(4000) NULL COMMENT '확정 응답 스냅샷(JSON) — 재시도 시 그대로 반환',
    created_at    DATETIME(6) NOT NULL,
    completed_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_idem_key (idem_key),
    CONSTRAINT fk_idem_member FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT = '멱등키 저장소 (실행·상환 등 돈이 움직이는 요청의 중복 방어)';

-- 중도상환수수료율: 상품이 보유하고, 실행 시점에 계약으로 스냅샷된다.
-- (금리 스냅샷과 같은 이유 — 은행이 수수료율을 바꿔도 기존 계약 조건은 불변)
ALTER TABLE loan_products
    ADD COLUMN prepayment_fee_rate DECIMAL(7, 6) NOT NULL DEFAULT 0.015000
        COMMENT '중도상환수수료율 (예: 0.015000 = 1.5%)' AFTER overdue_extra_rate;

ALTER TABLE loan_accounts
    ADD COLUMN prepayment_fee_rate DECIMAL(7, 6) NOT NULL DEFAULT 0.015000
        COMMENT '실행 시점 스냅샷' AFTER overdue_extra_rate;
