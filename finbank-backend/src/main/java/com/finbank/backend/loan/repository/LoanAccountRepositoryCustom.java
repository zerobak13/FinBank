package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.dto.LoanSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Spring Data가 자동 구현할 수 없는 쿼리를 담는 확장 인터페이스.
 *
 * <p>메서드 이름으로 쿼리를 만드는 방식은 조건이 3개만 넘어가도
 * {@code findByMemberIdAndStatusAndRepaymentTypeAndExecutedAtBetween...}처럼 무너진다.
 * 게다가 "값이 있을 때만 조건에 넣기"는 이름으로 표현할 방법이 아예 없다.</p>
 */
public interface LoanAccountRepositoryCustom {

    /**
     * 회원 본인의 대출을 조건 조합으로 검색한다.
     *
     * @param memberId  조회 주체 (다른 사람의 대출은 어떤 조건으로도 나오지 않는다)
     * @param condition null인 필드는 조건에서 제외된다
     */
    Page<LoanAccount> search(Long memberId, LoanSearchCondition condition, Pageable pageable);
}
