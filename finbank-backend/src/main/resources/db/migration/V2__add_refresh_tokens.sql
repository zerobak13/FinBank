-- =====================================================
-- V2: Refresh Token 테이블 추가
-- 생성일: 2025-04
-- 배경:
--   Access Token(JWT, 1시간) 만료 시 재로그인 없이
--   Refresh Token으로 재발급하는 구조 도입.
--
-- 설계 결정:
--   JWT refresh token 대신 UUID 불투명 토큰을 DB에 저장.
--   이유: JWT 방식은 탈취 시 만료 전까지 서버에서 막을 수 없으나,
--        DB 저장 방식은 logout 시 즉시 폐기 가능 → 금융 서비스에 적합.
--
-- Token Rotation 정책:
--   /api/auth/refresh 호출 시 기존 토큰 삭제 + 새 토큰 발급.
--   회원 1명당 최대 1개의 토큰 유지 (UNIQUE on member_id).
-- =====================================================

CREATE TABLE refresh_tokens
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    token      VARCHAR(36) NOT NULL COMMENT 'UUID v4',
    expires_at DATETIME(6) NOT NULL COMMENT '만료 시각 (7일)',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_member_id (member_id) COMMENT '1인 1토큰 보장',
    UNIQUE KEY uk_refresh_tokens_token (token),
    CONSTRAINT fk_refresh_tokens_member
        FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT = 'Refresh Token (UUID, DB 저장 방식)';
