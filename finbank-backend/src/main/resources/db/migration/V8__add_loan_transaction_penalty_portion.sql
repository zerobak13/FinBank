-- 대출 원장에 연체이자 칸을 따로 둔다.
--
-- 지금까지는 연체이자를 이자 부분(interest_portion)에 넣었다.
--   OVERDUE_INTEREST (배치가 쌓은 연체이자)    → interest_portion = 연체이자
--   REPAYMENT        (연체 회차를 갚은 상환)   → interest_portion = 약정 이자 + 연체이자
-- 그래서 원장에서 이자를 더하면 같은 연체이자가 쌓일 때 한 번, 받을 때 한 번 → 두 번 잡혔고,
-- 상환 한 줄만 봐서는 약정 이자와 연체이자를 나눌 수도 없었다.
--
-- 바뀐 규칙
--   - interest_portion에는 약정 이자만 (중도상환이면 경과이자 + 수수료). 연체이자는 penalty_portion에만.
--   - amount = principal_portion + interest_portion + penalty_portion (모든 유형).
--   - OVERDUE_INTEREST는 발생, REPAYMENT의 penalty_portion은 수납이다. 둘을 더하지 않는다.
--     받은 연체이자 = REPAYMENT의 penalty_portion 합, 쌓인 연체이자 = OVERDUE_INTEREST의 penalty_portion 합.
--
-- 기존 행 처리
--   - OVERDUE_INTEREST: 금액 전부가 연체이자이므로 칸만 옮긴다.
--   - REPAYMENT: 연체이자를 함께 받는 기능(PR #24) 이후의 연체 회차 상환 행은 이자 부분에 연체이자가
--     섞여 있지만, 원장에는 회차를 가리키는 칸이 없어 안전하게 나눌 수 없다. 그대로 둔다.
--     (개발·데모 DB에만 있을 수 있는 행이다. 잔액 계산은 원금 부분만 쓰므로 영향이 없다.)

ALTER TABLE loan_transactions
    ADD COLUMN penalty_portion DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '연체이자 부분 (OVERDUE_INTEREST=발생, REPAYMENT=수납)' AFTER interest_portion;

-- MySQL은 SET을 왼쪽부터 적용한다 — 칸을 먼저 옮기고 나서 0으로 비운다 (순서를 바꾸면 연체이자가 사라진다)
UPDATE loan_transactions
SET penalty_portion  = interest_portion,
    interest_portion = 0
WHERE type = 'OVERDUE_INTEREST';
