-- 대출 신청 시 "어느 계좌로 받을지"를 신청자가 지정하도록 한다.
--
-- 기존에는 실행 시점에 회원의 활성 계좌 중 첫 번째를 임의로 골랐다(resolveLinkedAccountId).
-- 계좌가 여러 개인 회원은 의도하지 않은 계좌로 대출금이 입금되고,
-- 이후 상환도 그 계좌에서 빠져나가므로 사용자가 명시적으로 고르게 바꾼다.
--
-- 기존 행 처리: 이미 신청된 건은 지정 계좌가 없으므로 NULL을 허용한다.
-- (NOT NULL로 만들면 기존 데이터가 있는 환경에서 마이그레이션이 실패한다.
--  실행 시점에 NULL이면 종전처럼 첫 계좌로 폴백해 하위 호환을 유지한다.)

ALTER TABLE loan_applications
    ADD COLUMN linked_account_id BIGINT NULL COMMENT '대출금을 입금받을 계좌 (NULL이면 구 데이터)' AFTER product_id;

ALTER TABLE loan_applications
    ADD CONSTRAINT fk_loan_app_linked_account
        FOREIGN KEY (linked_account_id) REFERENCES accounts (id);
