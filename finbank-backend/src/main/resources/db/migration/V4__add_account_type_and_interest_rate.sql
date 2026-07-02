-- =====================================================
-- V4: 계좌 타입 및 이자율 컬럼 추가
-- 생성일: 2025-05
-- 배경:
--   Spring Batch 이자 정산 배치를 도입하기 위해
--   계좌 타입(일반/적금)과 연이율 컬럼을 추가.
--
-- 설계 결정:
--   - account_type: REGULAR(일반) / SAVINGS(적금)
--   - interest_rate: 연이율 (0.0200 = 2%). 소수점 4자리 정밀도.
--   - 기존 계좌는 모두 REGULAR / 0.0000 으로 초기화.
--   - 배치는 account_type = 'SAVINGS' 인 계좌만 처리.
-- =====================================================

ALTER TABLE accounts
    ADD COLUMN account_type  VARCHAR(20)    NOT NULL DEFAULT 'REGULAR'
        COMMENT 'REGULAR(일반) | SAVINGS(적금)',
    ADD COLUMN interest_rate DECIMAL(5, 4)  NOT NULL DEFAULT 0.0000
        COMMENT '연이율 (ex: 0.0200 = 2%). 배치 이자 정산에 사용.';
