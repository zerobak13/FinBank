package com.finbank.backend.loan.repository;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import com.finbank.backend.support.TestAccountNumbers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인덱스 성능 측정 — C4의 숫자.
 *
 * <p>10만 건을 한 회원 앞으로 적재하고, 검색 쿼리를 <b>복합 인덱스 없이 / 있게</b> 두 번 재서
 * 실행계획(사용 인덱스, 조사 행 수)과 응답시간을 비교한다.</p>
 *
 * <p><b>"인덱스 없음"이 정확히 무엇인지</b>: member_id에는 FK 제약 때문에 이미 단일 인덱스가 있다.
 * 따라서 이 측정의 "전"은 인덱스가 아예 없는 상태가 아니라 <b>FK 인덱스만 있는 상태</b>다.
 * 회원 한 명이 대출을 많이 가질수록 FK 인덱스는 무력해진다 — 회원의 대출을 전부 꺼낸 뒤
 * 상태와 기간을 하나씩 걸러야 하기 때문이다.</p>
 *
 * <p>매 커밋마다 돌릴 테스트가 아니므로 {@code perf} 태그로 분리했다: {@code ./gradlew perfTest}</p>
 */
@SpringBootTest
@Tag("perf")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoanSearchPerformanceTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LoanProductRepository loanProductRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "perf@test.com";
    private static final String INDEX_NAME = "idx_loan_accounts_member_status_executed";
    private static final int ROWS = 100_000;
    private static final int WARMUP = 5;
    private static final int RUNS = 20;

    /**
     * 검색 조건: 최근 30일 안에 실행된 정상 대출.
     * 10만 건 중 약 2~3%만 해당하도록 잡았다 — 인덱스가 "얼마나 안 읽어도 되게 해주는가"를 보려면
     * 조건이 실제로 데이터를 걸러야 한다. 전체의 절반이 걸리는 조건은 인덱스를 타도 의미가 없다.
     */
    private static final String LIST_SQL = """
            SELECT l.id FROM loan_accounts l %s
             WHERE l.member_id = ? AND l.status = 'ACTIVE'
               AND l.executed_at BETWEEN ? AND ?
             ORDER BY l.id DESC LIMIT 10
            """;

    private static final String COUNT_SQL = """
            SELECT COUNT(*) FROM loan_accounts l %s
             WHERE l.member_id = ? AND l.status = 'ACTIVE'
               AND l.executed_at BETWEEN ? AND ?
            """;

    /**
     * "인덱스 없음" 상태를 만드는 방법 — DROP이 아니라 {@code IGNORE INDEX}.
     *
     * <p>처음엔 인덱스를 DROP했다가 다시 만들려 했지만 MySQL이 거부했다(에러 1553:
     * {@code needed in a foreign key constraint}). loan_accounts.member_id에는 FK가 걸려 있고,
     * 이 복합 인덱스가 그 FK를 지탱하는 인덱스로 쓰이고 있었기 때문이다.</p>
     *
     * <p>결과적으로 더 나은 방법이 됐다. 측정하려던 것은 "인덱스가 물리적으로 없을 때"가 아니라
     * <b>"옵티마이저가 이 인덱스를 못 쓸 때"</b>이고, IGNORE INDEX는 스키마를 전혀 건드리지 않는다.
     * 측정 도중 테스트가 죽어도 DB가 반쪽 상태로 남지 않는다.</p>
     */
    private static final String IGNORE_HINT = "IGNORE INDEX (" + INDEX_NAME + ")";

    private Long memberId;
    private Object[] params;

    @BeforeEach
    void setUp() {
        cleaner.clean();

        Member member = memberRepository.saveAndFlush(new Member(EMAIL, "성능테스터", "password"));
        Account account = accountRepository.saveAndFlush(
                new Account(member, TestAccountNumbers.next(), new BigDecimal("1000000")));
        LoanProduct product = loanProductRepository.findByStatus(ProductStatus.ON_SALE).get(0);

        memberId = member.getId();
        LocalDate today = LocalDate.now();
        params = new Object[]{memberId, today.minusDays(30), today};

        long start = System.currentTimeMillis();
        seed(product.getId(), account.getId());
        System.out.printf("%n[seed] %,d건 적재 %,dms%n", ROWS, System.currentTimeMillis() - start);
    }

    @AfterEach
    void tearDown() {
        cleaner.clean();
    }

    @Test
    @DisplayName("인덱스 전/후 — 실행계획과 응답시간 비교")
    void compareIndexEffect() {
        printIndexes();

        Measurement beforeCount = measure(COUNT_SQL.formatted(IGNORE_HINT));
        Measurement beforeList = measure(LIST_SQL.formatted(IGNORE_HINT));

        Measurement afterCount = measure(COUNT_SQL.formatted(""));
        Measurement afterList = measure(LIST_SQL.formatted(""));

        printTable(beforeCount, afterCount, beforeList, afterList);

        // 카운트 쿼리는 정렬이 개입하지 않아 인덱스 효과가 그대로 드러난다.
        assertThat(afterCount.key).isEqualTo(INDEX_NAME);
        assertThat(afterCount.rows)
                .as("복합 인덱스가 조사 행 수를 크게 줄여야 한다")
                .isLessThan(beforeCount.rows / 5);
    }

    // ── 측정 ─────────────────────────────────────────────────────

    private Measurement measure(String sql) {
        Map<String, Object> plan = jdbc.queryForMap("EXPLAIN " + sql, params);

        for (int i = 0; i < WARMUP; i++) {
            jdbc.queryForList(sql, params);
        }
        long elapsed = 0;
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            jdbc.queryForList(sql, params);
            elapsed += System.nanoTime() - t0;
        }

        return new Measurement(
                String.valueOf(plan.get("key")),
                plan.get("rows") == null ? 0 : ((Number) plan.get("rows")).longValue(),
                String.valueOf(plan.get("type")),
                elapsed / RUNS / 1_000_000.0);
    }

    private record Measurement(String key, long rows, String type, double avgMs) {
    }

    private void printTable(Measurement beforeCount, Measurement afterCount,
                            Measurement beforeList, Measurement afterList) {
        System.out.println();
        System.out.println("=== 인덱스 성능 비교 (loan_accounts " + String.format("%,d", ROWS) + "건) ===");
        System.out.printf("%-14s %-14s %-40s %12s %10s%n", "쿼리", "인덱스", "사용 인덱스(type)", "조사 행 수", "평균(ms)");
        row("COUNT", "미사용", beforeCount);
        row("COUNT", "복합 인덱스", afterCount);
        row("목록+정렬", "미사용", beforeList);
        row("목록+정렬", "복합 인덱스", afterList);
        System.out.printf("%n  조사 행 수 %,d → %,d (%.1f배 감소), 응답시간 %.2fms → %.2fms%n",
                beforeCount.rows(), afterCount.rows(),
                beforeCount.rows() / (double) Math.max(1, afterCount.rows()),
                beforeCount.avgMs(), afterCount.avgMs());
        System.out.println();
    }

    private void row(String query, String label, Measurement m) {
        System.out.printf("%-14s %-14s %-40s %,12d %10.2f%n",
                query, label, m.key() + " (" + m.type() + ")", m.rows(), m.avgMs());
    }

    // ── 인덱스 현황 ────────────────────────────────────────────────

    /** 어떤 인덱스가 후보로 존재하는지 함께 남긴다 — 측정 결과를 나중에 해석하려면 이 정보가 필요하다 */
    private void printIndexes() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'loan_accounts'
                 GROUP BY index_name
                """);
        System.out.println();
        System.out.println("[loan_accounts 인덱스 목록]");
        rows.forEach(r -> System.out.printf("  %-40s (%s)%n", r.get("index_name"), r.get("cols")));
    }

    // ── 시드 ─────────────────────────────────────────────────────

    /**
     * 재귀 CTE로 10만 건을 한 번에 적재한다.
     *
     * <p>JPA로 10만 건을 넣으면 영속성 컨텍스트와 왕복 비용 때문에 측정 준비에만 수 분이 걸린다.
     * 측정 대상은 애플리케이션이 아니라 <b>DB의 실행계획</b>이므로 적재도 DB 안에서 끝낸다.</p>
     *
     * <p>{@code SET SESSION}과 INSERT는 <b>같은 커넥션</b>에서 실행해야 한다.
     * JdbcTemplate은 호출마다 커넥션을 풀에서 새로 빌려오므로 ConnectionCallback으로 묶는다.</p>
     */
    private void seed(Long productId, Long accountId) {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET SESSION cte_max_recursion_depth = " + (ROWS * 2));

                st.executeUpdate("""
                        INSERT INTO loan_applications
                            (member_id, product_id, requested_amount, term_months, status, applied_at)
                        WITH RECURSIVE seq AS (
                            SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < %d
                        )
                        SELECT %d, %d, 1000000 + (n %% 40) * 1000000, 12, 'EXECUTED', NOW(6) FROM seq
                        """.formatted(ROWS, memberId, productId));

                // 상태는 8:1:1(정상/연체/완납), 실행일은 최근 900일에 분산 — 조건이 실제로 걸러지도록
                st.executeUpdate("""
                        INSERT INTO loan_accounts
                            (application_id, member_id, linked_account_id, principal, balance,
                             interest_rate, overdue_extra_rate, prepayment_fee_rate, repayment_type,
                             executed_at, maturity_date, status)
                        SELECT a.id, a.member_id, %d, a.requested_amount, a.requested_amount,
                               0.059000, 0.030000, 0.015000,
                               CASE a.id %% 3 WHEN 0 THEN 'EQUAL_PAYMENT'
                                              WHEN 1 THEN 'EQUAL_PRINCIPAL'
                                              ELSE 'BULLET' END,
                               DATE_SUB(CURDATE(), INTERVAL (a.id %% 900) DAY),
                               DATE_ADD(DATE_SUB(CURDATE(), INTERVAL (a.id %% 900) DAY), INTERVAL 12 MONTH),
                               CASE a.id %% 10 WHEN 0 THEN 'OVERDUE'
                                               WHEN 1 THEN 'PAID_OFF'
                                               ELSE 'ACTIVE' END
                          FROM loan_applications a
                         WHERE a.member_id = %d
                        """.formatted(accountId, memberId));

                // 옵티마이저가 낡은 통계로 판단하지 않도록 갱신
                st.execute("ANALYZE TABLE loan_accounts");
            }
            return null;
        });

        List<Map<String, Object>> check = jdbc.queryForList(
                "SELECT COUNT(*) cnt FROM loan_accounts WHERE member_id = ?", memberId);
        assertThat(((Number) check.get(0).get("cnt")).intValue()).isEqualTo(ROWS);
    }
}
