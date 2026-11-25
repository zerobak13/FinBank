package com.finbank.backend.loan.service;

import com.finbank.backend.common.PageResponse;
import com.finbank.backend.common.PagingPolicy;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.dto.LoanDetailResponse;
import com.finbank.backend.loan.dto.LoanSearchCondition;
import com.finbank.backend.loan.dto.LoanSummaryResponse;
import com.finbank.backend.loan.dto.RepaymentScheduleResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.service.CurrentMemberProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 실행된 대출의 조회 전용 서비스 (목록 / 상세+스케줄) */
@Service
@Transactional(readOnly = true)
public class LoanQueryService {

    private final LoanAccountRepository loanAccountRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final OverdueHistoryRepository overdueHistoryRepository;
    private final CurrentMemberProvider currentMember;

    public LoanQueryService(LoanAccountRepository loanAccountRepository,
                            RepaymentScheduleRepository repaymentScheduleRepository,
                            OverdueHistoryRepository overdueHistoryRepository,
                            CurrentMemberProvider currentMember) {
        this.loanAccountRepository = loanAccountRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.overdueHistoryRepository = overdueHistoryRepository;
        this.currentMember = currentMember;
    }

    public List<LoanSummaryResponse> getMyLoans() {
        Member member = currentMember.current();
        return loanAccountRepository.findByMemberIdOrderByIdDesc(member.getId()).stream()
                .map(LoanSummaryResponse::from)
                .toList();
    }

    /**
     * 내 대출 조건 검색 (QueryDSL 동적 쿼리).
     * 조건이 비어 있으면 전체 목록과 같은 결과가 페이징되어 나온다.
     */
    public PageResponse<LoanSummaryResponse> search(LoanSearchCondition condition, int page, int size) {
        Pageable pageable = PagingPolicy.of(page, size);
        Member member = currentMember.current();

        Page<LoanSummaryResponse> result = loanAccountRepository
                .search(member.getId(), condition, pageable)
                .map(LoanSummaryResponse::from);

        return new PageResponse<>(result);
    }

    public LoanDetailResponse getLoan(Long loanId) {
        LoanAccount loan = getOwnLoan(loanId);
        return LoanDetailResponse.of(loan, schedulesOf(loan));
    }

    public List<RepaymentScheduleResponse> getSchedules(Long loanId) {
        return schedulesOf(getOwnLoan(loanId));
    }

    /** 연체 회차에는 지금까지 쌓인 연체이자를 같이 내려 준다 — 화면의 상환 금액이 실제 출금액과 같아야 한다 */
    private List<RepaymentScheduleResponse> schedulesOf(LoanAccount loan) {
        Map<Long, BigDecimal> penaltyBySchedule = overdueHistoryRepository
                .findByLoanAccountAndResolvedAtIsNull(loan).stream()
                .collect(Collectors.toMap(h -> h.getSchedule().getId(), OverdueHistory::getPenaltyAccrued, BigDecimal::add));
        return repaymentScheduleRepository.findByLoanAccountOrderByInstallmentNoAsc(loan).stream()
                .map(s -> RepaymentScheduleResponse.from(s, penaltyBySchedule.getOrDefault(s.getId(), BigDecimal.ZERO)))
                .toList();
    }

    private LoanAccount getOwnLoan(Long loanId) {
        Member member = currentMember.current();
        LoanAccount loan = loanAccountRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("대출을 찾을 수 없습니다: " + loanId));
        if (!loan.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인의 대출만 조회할 수 있습니다.");
        }
        return loan;
    }
}
