package com.finbank.backend.loan.service;

import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.loan.domain.LoanAccount;
import com.finbank.backend.loan.dto.LoanDetailResponse;
import com.finbank.backend.loan.dto.LoanSummaryResponse;
import com.finbank.backend.loan.dto.RepaymentScheduleResponse;
import com.finbank.backend.loan.repository.LoanAccountRepository;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.service.CurrentMemberProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 실행된 대출의 조회 전용 서비스 (목록 / 상세+스케줄) */
@Service
@Transactional(readOnly = true)
public class LoanQueryService {

    private final LoanAccountRepository loanAccountRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final CurrentMemberProvider currentMember;

    public LoanQueryService(LoanAccountRepository loanAccountRepository,
                            RepaymentScheduleRepository repaymentScheduleRepository,
                            CurrentMemberProvider currentMember) {
        this.loanAccountRepository = loanAccountRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.currentMember = currentMember;
    }

    public List<LoanSummaryResponse> getMyLoans() {
        Member member = currentMember.current();
        return loanAccountRepository.findByMemberIdOrderByIdDesc(member.getId()).stream()
                .map(LoanSummaryResponse::from)
                .toList();
    }

    public LoanDetailResponse getLoan(Long loanId) {
        LoanAccount loan = getOwnLoan(loanId);
        return LoanDetailResponse.of(loan, schedulesOf(loan));
    }

    public List<RepaymentScheduleResponse> getSchedules(Long loanId) {
        return schedulesOf(getOwnLoan(loanId));
    }

    private List<RepaymentScheduleResponse> schedulesOf(LoanAccount loan) {
        return repaymentScheduleRepository.findByLoanAccountOrderByInstallmentNoAsc(loan).stream()
                .map(RepaymentScheduleResponse::from)
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
