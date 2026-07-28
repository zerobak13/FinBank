-- =====================================================
-- V3: 금액 컬럼 타입 전환 (BIGINT → DECIMAL(19,4))
--
-- [배경]
-- 여신(대출) 모듈의 이자 계산은 소수 연산이 필요하다.
-- 금액을 BIGINT(원 단위 정수)로 유지한 채 여신을 붙이면
-- 모듈마다 금액 타입이 갈라져 재작업이 커지므로, 여신 도입 "직전"에 전환한다.
--
-- [DECIMAL(19,4)인 이유]
-- KRW는 소수 단위가 없지만, 이자·수수료의 중간 계산값과
-- 연체이자 누적(penalty) 등이 소수를 가질 수 있어 여유 스케일 4를 둔다.
-- 저장되는 확정 금액은 정책상 항상 원 단위 정수다(MoneyPolicy: 원 미만 절사).
--
-- [안전성]
-- BIGINT → DECIMAL(19,4)는 값이 보존되는 확장 변환이다. (예: 1000 → 1000.0000)
-- =====================================================

ALTER TABLE accounts
    MODIFY COLUMN balance DECIMAL(19,4) NOT NULL COMMENT '잔액(원)';

ALTER TABLE transaction_logs
    MODIFY COLUMN amount DECIMAL(19,4) NOT NULL COMMENT '거래 금액(원)',
    MODIFY COLUMN balance_after DECIMAL(19,4) NOT NULL COMMENT '거래 후 잔액(원)';
