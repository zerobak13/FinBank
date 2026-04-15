-- =====================================================
-- V1: 초기 스키마 (FinBank 프로젝트 최초 테이블 구성)
-- 생성일: 2025-01
-- 포함 테이블: members, accounts, transaction_logs
--
-- [Flyway 도입 전 이미 DB가 존재하는 경우]
-- application.yml의 baseline-on-migrate: true 설정으로
-- 이 파일은 baseline으로 마킹되어 실행되지 않습니다.
-- [빈 DB에서 최초 실행 시]
-- 이 파일이 먼저 실행되어 전체 스키마가 생성됩니다.
-- =====================================================

-- 회원 테이블
CREATE TABLE members
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(100) NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    password   VARCHAR(200) NOT NULL COMMENT 'BCrypt 해시',
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_email (email)
) COMMENT = '회원';

-- 계좌 테이블
CREATE TABLE accounts
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    account_number VARCHAR(20) NOT NULL COMMENT '12자리 계좌번호',
    member_id      BIGINT      NOT NULL,
    balance        BIGINT      NOT NULL COMMENT '잔액(원)',
    locked         BIT         NOT NULL DEFAULT 0 COMMENT '계좌 잠금 여부 (DB 락과 무관)',
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_account_number (account_number),
    CONSTRAINT fk_accounts_member
        FOREIGN KEY (member_id) REFERENCES members (id)
) COMMENT = '계좌';

-- 거래 로그 테이블
-- 입금/출금/이체를 단일 테이블로 통합 관리
-- balance_after: 거래 후 잔액을 기록하여 시점별 잔액 이력 추적 가능
CREATE TABLE transaction_logs
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    type            VARCHAR(20) NOT NULL COMMENT 'DEPOSIT | WITHDRAW | TRANSFER_IN | TRANSFER_OUT',
    from_account_id BIGINT      NULL COMMENT '출금 계좌 (입금 시 NULL)',
    to_account_id   BIGINT      NULL COMMENT '입금 계좌 (출금 시 NULL)',
    amount          BIGINT      NOT NULL COMMENT '거래 금액(원)',
    balance_after   BIGINT      NOT NULL COMMENT '거래 후 잔액(원)',
    description     VARCHAR(255) NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tx_from_account
        FOREIGN KEY (from_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_tx_to_account
        FOREIGN KEY (to_account_id) REFERENCES accounts (id)
) COMMENT = '거래 로그';
