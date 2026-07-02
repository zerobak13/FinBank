-- =====================================================
-- V3: 멱등성 키 테이블 추가
-- 생성일: 2025-05
-- 배경:
--   이체/입금/출금 요청이 네트워크 오류 등으로 중복 전송될 경우
--   동일한 요청이 두 번 실행되는 것을 방지하기 위해 도입.
--
-- 설계 결정:
--   클라이언트가 요청 시 Idempotency-Key 헤더(UUID v4)를 전송.
--   서버는 (idempotency_key, member_id) 복합 유니크 제약으로
--   동일 회원의 중복 요청을 DB 레벨에서 차단.
--
-- 처리 흐름:
--   1. 키 선조회 → 완료된 기록 있으면 캐시된 응답 반환
--   2. 없으면 키를 먼저 INSERT (처리 중 상태)
--   3. 실제 비즈니스 로직 실행
--   4. 성공 시 응답 저장, 실패 시 키 삭제 (재시도 허용)
--
-- 만료 정책:
--   expires_at 기준 24시간 후 만료 (배치 or 조회 시점에 필터링)
-- =====================================================

CREATE TABLE idempotency_keys
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(36)  NOT NULL COMMENT 'UUID v4 - 클라이언트 발급',
    member_id       BIGINT       NOT NULL,
    request_path    VARCHAR(255) NOT NULL COMMENT '요청 경로 (ex: /api/accounts/transfer)',
    response_status INT          NOT NULL DEFAULT 0 COMMENT 'HTTP 상태코드 (0=처리중, 나머지=완료)',
    response_body   TEXT         NULL COMMENT '응답 JSON - 완료 후 기록',
    created_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NOT NULL COMMENT '24시간 후 만료',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_member_key (idempotency_key, member_id) COMMENT '동일 회원의 중복 키 방지',
    CONSTRAINT fk_idempotency_member
        FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT = '멱등성 키 저장소 - 중복 요청 방지';
