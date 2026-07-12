package com.finbank.backend.loan.repository;

import com.finbank.backend.domain.Member;
import com.finbank.backend.loan.domain.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    /** 회원의 신청 이력 (최신순) */
    List<LoanApplication> findByMemberOrderByAppliedAtDesc(Member member);
}
