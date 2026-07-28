-- =====================================================
-- V4: 여신(대출) 모듈 스키마
--
-- [설계 원칙]
-- 1. 상품(loan_products)과 계약(loan_accounts) 분리 — 실행 시점에 금리/상환방식을
--    계약으로 "복사(스냅샷)"해서 상품 변경이 기존 계약에 영향을 주지 않게 한다.
-- 2. loan_transactions는 append-only 원장 — UPDATE/DELETE 금지. 잔액은 파생값이다.
-- 3. 상환 스케줄은 실행 시점에 전 회차 확정 생성 — 이자 엔진의 산출물이 DB에 남아
--    은행 계산기와 대조 검증이 가능하다.
-- =====================================================

-- 대출 상품
CREATE TABLE loan_products
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    name               VARCHAR(100)  NOT NULL,
    repayment_type     VARCHAR(20)   NOT NULL COMMENT 'EQUAL_PAYMENT(원리금균등) | EQUAL_PRINCIPAL(원금균등) | BULLET(만기일시)',
    interest_rate      DECIMAL(7,6)  NOT NULL COMMENT '연이율 (예: 0.059000 = 연 5.9%)',
    overdue_extra_rate DECIMAL(7,6)  NOT NULL COMMENT '연체 가산율',
    min_amount         DECIMAL(19,4) NOT NULL,
    max_amount         DECIMAL(19,4) NOT NULL,
    max_term_months    INT           NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE | CLOSED',
    created_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) COMMENT = '대출 상품';

-- 대출 신청
CREATE TABLE loan_applications
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    member_id        BIGINT        NOT NULL,
    product_id       BIGINT        NOT NULL,
    requested_amount DECIMAL(19,4) NOT NULL,
    term_months      INT           NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED | APPROVED | REJECTED | EXECUTED | CANCELED',
    reject_reason    VARCHAR(50)   NULL COMMENT '자동심사 탈락 룰 코드',
    applied_at       DATETIME(6)   NOT NULL,
    reviewed_at      DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_loan_app_member  FOREIGN KEY (member_id)  REFERENCES members (id),
    CONSTRAINT fk_loan_app_product FOREIGN KEY (product_id) REFERENCES loan_products (id)
) COMMENT = '대출 신청';

-- 대출 원장(계좌)
CREATE TABLE loan_accounts
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    application_id     BIGINT        NOT NULL COMMENT '신청 1건당 실행 1건 (UNIQUE = 이중 실행의 DB 레벨 최종 방어)',
    member_id          BIGINT        NOT NULL,
    linked_account_id  BIGINT        NOT NULL COMMENT '실행 시 입금 / 상환 시 출금할 입출금 계좌',
    principal          DECIMAL(19,4) NOT NULL COMMENT '실행 원금',
    balance            DECIMAL(19,4) NOT NULL COMMENT '미상환 원금',
    interest_rate      DECIMAL(7,6)  NOT NULL COMMENT '실행 시점 스냅샷 (상품 변경과 무관)',
    overdue_extra_rate DECIMAL(7,6)  NOT NULL COMMENT '실행 시점 스냅샷',
    repayment_type     VARCHAR(20)   NOT NULL COMMENT '실행 시점 스냅샷',
    executed_at        DATE          NOT NULL,
    maturity_date      DATE          NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | OVERDUE | PAID_OFF',
    PRIMARY KEY (id),
    UNIQUE KEY uq_loan_account_application (application_id),
    CONSTRAINT fk_loan_acc_app    FOREIGN KEY (application_id)    REFERENCES loan_applications (id),
    CONSTRAINT fk_loan_acc_member FOREIGN KEY (member_id)         REFERENCES members (id),
    CONSTRAINT fk_loan_acc_linked FOREIGN KEY (linked_account_id) REFERENCES accounts (id)
) COMMENT = '대출 원장';

-- 상환 스케줄 (실행 시 전 회차 확정 생성)
CREATE TABLE repayment_schedules
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    loan_account_id  BIGINT        NOT NULL,
    installment_no   INT           NOT NULL COMMENT '회차 (1부터)',
    due_date         DATE          NOT NULL,
    principal_amount DECIMAL(19,4) NOT NULL,
    interest_amount  DECIMAL(19,4) NOT NULL,
    total_amount     DECIMAL(19,4) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | PAID | OVERDUE | CANCELED',
    paid_at          DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_schedule_installment (loan_account_id, installment_no),
    CONSTRAINT fk_sched_loan FOREIGN KEY (loan_account_id) REFERENCES loan_accounts (id)
) COMMENT = '상환 스케줄';

-- 대출 거래 원장 (append-only)
CREATE TABLE loan_transactions
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    loan_account_id   BIGINT        NOT NULL,
    type              VARCHAR(20)   NOT NULL COMMENT 'EXECUTION | REPAYMENT | PREPAYMENT | OVERDUE_INTEREST',
    amount            DECIMAL(19,4) NOT NULL,
    principal_portion DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '원금 부분',
    interest_portion  DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '이자 부분',
    balance_after     DECIMAL(19,4) NOT NULL COMMENT '거래 직후 미상환 원금',
    idempotency_key   VARCHAR(64)   NULL COMMENT '멱등키 (UNIQUE = 중복 기록의 DB 레벨 최종 방어)',
    created_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_loan_tx_idem (idempotency_key),
    CONSTRAINT fk_loan_tx_loan FOREIGN KEY (loan_account_id) REFERENCES loan_accounts (id)
) COMMENT = '대출 거래 원장';

-- 연체 이력
CREATE TABLE overdue_histories
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    loan_account_id BIGINT        NOT NULL,
    schedule_id     BIGINT        NOT NULL,
    overdue_start   DATE          NOT NULL,
    resolved_at     DATE          NULL COMMENT 'NULL이면 연체 진행 중',
    overdue_amount  DECIMAL(19,4) NOT NULL COMMENT '연체 원리금',
    penalty_accrued DECIMAL(19,4) NOT NULL DEFAULT 0 COMMENT '가산된 연체이자 누계',
    last_accrued_at DATE          NULL COMMENT '배치 이중 가산 방지용 (마지막 가산일)',
    PRIMARY KEY (id),
    CONSTRAINT fk_overdue_loan  FOREIGN KEY (loan_account_id) REFERENCES loan_accounts (id),
    CONSTRAINT fk_overdue_sched FOREIGN KEY (schedule_id)     REFERENCES repayment_schedules (id)
) COMMENT = '연체 이력';

-- 상품 시드 3종 (상환방식별 1개씩)
INSERT INTO loan_products (name, repayment_type, interest_rate, overdue_extra_rate, min_amount, max_amount, max_term_months, created_at) VALUES
('직장인 신용대출 (원리금균등)', 'EQUAL_PAYMENT',   0.059000, 0.030000, 1000000, 50000000, 60, NOW(6)),
('신용대출 (원금균등)',          'EQUAL_PRINCIPAL', 0.055000, 0.030000, 1000000, 30000000, 36, NOW(6)),
('단기 신용대출 (만기일시)',     'BULLET',          0.068000, 0.030000, 1000000, 20000000, 12, NOW(6));
