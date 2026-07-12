package com.finbank.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 통합 테스트용 DB 정리기 — 삭제 순서의 단일 출처.
 *
 * <p>배경: 여신 테이블 추가 후, 각 테스트에 흩어져 있던 정리 로직이
 * 새 FK(loan_applications → members)를 몰라 부모 행 삭제가 실패했다.
 * 테이블이 늘 때마다 모든 테스트를 고치지 않도록 삭제 순서를 여기 한 곳에만 둔다.</p>
 *
 * <p>삭제 순서: FK 자식 → 부모. loan_products 시드는 유지한다.</p>
 */
@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clean() {
        jdbcTemplate.update("DELETE FROM loan_transactions");
        jdbcTemplate.update("DELETE FROM overdue_histories");
        jdbcTemplate.update("DELETE FROM repayment_schedules");
        jdbcTemplate.update("DELETE FROM loan_accounts");
        jdbcTemplate.update("DELETE FROM loan_applications");
        jdbcTemplate.update("DELETE FROM transaction_logs");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM members");
    }
}
