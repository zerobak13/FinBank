package com.finbank.backend.support;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.member.domain.Member;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * 테스트 데이터: "실행된" 대출 신청을 만든다.
 *
 * <p>실행 전이(APPROVED → EXECUTED)는 운영에서 조건부 UPDATE
 * ({@link LoanApplicationRepository#markExecutedIfApproved})로만 일어난다 — 두 요청이 같은 신청을
 * 동시에 실행하는 경쟁을 DB 한 문장으로 막기 위해서다. 그래서 도메인에는 이 전이 메서드가 없고,
 * 테스트 데이터도 같은 UPDATE로 만든다. 운영과 다른 길로 상태를 만들면 두 길이 어긋나도 테스트가 못 잡는다.</p>
 */
@Component
public class LoanApplicationFixture {

    private final LoanApplicationRepository loanApplicationRepository;
    private final TransactionTemplate tx;

    public LoanApplicationFixture(LoanApplicationRepository loanApplicationRepository,
                                  PlatformTransactionManager transactionManager) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 신청 → 승인 → 저장 → 조건부 UPDATE로 실행 완료.
     *
     * @return 실행 상태로 다시 읽은 신청. 상품·회원·입금 계좌를 미리 읽어 두므로
     *         트랜잭션 밖에서 {@code LoanAccount.execute(...)}에 바로 넘길 수 있다.
     */
    public LoanApplication executed(Member member, LoanProduct product, Account linkedAccount,
                                    BigDecimal amount, int termMonths) {
        LoanApplication application = new LoanApplication(member, product, linkedAccount, amount, termMonths);
        application.approve();
        Long id = loanApplicationRepository.saveAndFlush(application).getId();

        return tx.execute(status -> {
            if (loanApplicationRepository.markExecutedIfApproved(id) != 1) {
                throw new IllegalStateException("신청을 실행 상태로 바꾸지 못했습니다: " + id);
            }
            LoanApplication executed = loanApplicationRepository.findById(id).orElseThrow();
            Hibernate.initialize(executed.getMember());
            Hibernate.initialize(executed.getProduct());
            Hibernate.initialize(executed.getLinkedAccount());
            return executed;
        });
    }
}
