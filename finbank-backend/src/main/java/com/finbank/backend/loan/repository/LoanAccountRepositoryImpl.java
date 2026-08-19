package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.dto.LoanSearchCondition;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.finbank.backend.loan.domain.QLoanAccount.loanAccount;
import static com.finbank.backend.loan.domain.QLoanApplication.loanApplication;

/**
 * 대출 동적 검색 구현.
 *
 * <p><b>클래스 이름이 규칙이다</b>: Spring Data는 리포지토리 인터페이스명 + {@code Impl}을 찾아
 * 자동 생성 구현체와 합쳐준다. 이름이 틀리면 조용히 빈 등록에 실패한다.</p>
 *
 * <p><b>조건 조립</b>: 각 조건 메서드는 값이 없으면 {@code null}을 반환하고,
 * QueryDSL의 {@code where(...)}는 null 인자를 무시한다. if문 없이 "있으면 붙고 없으면 빠지는"
 * 구조가 되며, 조건이 늘어도 메서드 하나만 추가하면 된다.</p>
 *
 * <p><b>페치 조인과 페이징</b>: 여기서 조인하는 application·product는 모두 <b>단일 연관(to-one)</b>이라
 * 페치 조인과 offset/limit를 함께 써도 DB가 그대로 잘라준다. 만약 컬렉션(to-many)을 페치 조인하면
 * 하이버네이트가 전체를 읽어 메모리에서 페이징하므로(HHH90003004 경고) 10만 건에서 바로 터진다.</p>
 */
public class LoanAccountRepositoryImpl implements LoanAccountRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public LoanAccountRepositoryImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public Page<LoanAccount> search(Long memberId, LoanSearchCondition condition, Pageable pageable) {
        List<LoanAccount> content = queryFactory
                .selectFrom(loanAccount)
                .join(loanAccount.application, loanApplication).fetchJoin()
                .join(loanApplication.product).fetchJoin()
                .where(
                        loanAccount.member.id.eq(memberId),          // 본인 대출만 — 협상 불가 조건
                        statusEq(condition.status()),
                        repaymentTypeEq(condition.repaymentType()),
                        productEq(condition.productId()),
                        executedGoe(condition.executedFrom()),
                        executedLoe(condition.executedTo()),
                        principalGoe(condition.minPrincipal()),
                        principalLoe(condition.maxPrincipal())
                )
                .orderBy(loanAccount.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count 쿼리는 페치 조인 없이 (조인은 productId 조건이 있을 때만 의미가 있지만,
        // 조건 유무와 무관하게 동일한 결과를 보장하기 위해 같은 조인 구조를 유지한다)
        JPAQuery<Long> countQuery = queryFactory
                .select(loanAccount.count())
                .from(loanAccount)
                .join(loanAccount.application, loanApplication)
                .where(
                        loanAccount.member.id.eq(memberId),
                        statusEq(condition.status()),
                        repaymentTypeEq(condition.repaymentType()),
                        productEq(condition.productId()),
                        executedGoe(condition.executedFrom()),
                        executedLoe(condition.executedTo()),
                        principalGoe(condition.minPrincipal()),
                        principalLoe(condition.maxPrincipal())
                );

        // 마지막 페이지이거나 첫 페이지가 다 차지 않으면 count 쿼리를 아예 실행하지 않는다.
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // ── 조건 조각들 — 값이 없으면 null을 돌려주고, where()가 알아서 건너뛴다 ──

    private BooleanExpression statusEq(LoanAccountStatus status) {
        return status == null ? null : loanAccount.status.eq(status);
    }

    private BooleanExpression repaymentTypeEq(RepaymentType type) {
        return type == null ? null : loanAccount.repaymentType.eq(type);
    }

    private BooleanExpression productEq(Long productId) {
        return productId == null ? null : loanApplication.product.id.eq(productId);
    }

    private BooleanExpression executedGoe(LocalDate from) {
        return from == null ? null : loanAccount.executedAt.goe(from);
    }

    private BooleanExpression executedLoe(LocalDate to) {
        return to == null ? null : loanAccount.executedAt.loe(to);
    }

    private BooleanExpression principalGoe(BigDecimal min) {
        return min == null ? null : loanAccount.principal.goe(min);
    }

    private BooleanExpression principalLoe(BigDecimal max) {
        return max == null ? null : loanAccount.principal.loe(max);
    }
}
