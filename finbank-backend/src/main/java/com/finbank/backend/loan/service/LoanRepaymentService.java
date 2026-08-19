package com.finbank.backend.loan.service;

import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.common.idempotency.RequestHash;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.member.service.CurrentMemberProvider;
import org.springframework.stereotype.Service;

/**
 * 상환 API의 진입점 — 멱등 처리를 감싸고 실제 처리는 {@link LoanRepaymentProcessor}에 위임한다.
 *
 * <p>요청 해시에 회차 번호까지 포함하는 이유: 같은 멱등키로 1회차와 2회차를 보내는 것은
 * 클라이언트 버그다. 해시가 다르면 422로 즉시 알려준다(조용히 1회차 응답을 돌려주면
 * 사용자는 2회차가 납부됐다고 착각한다).</p>
 */
@Service
public class LoanRepaymentService {

    private static final String REPAY = "loan-repay";
    private static final String PREPAY = "loan-prepay";

    private final LoanRepaymentProcessor processor;
    private final IdempotentExecutor idempotentExecutor;
    private final CurrentMemberProvider currentMember;

    public LoanRepaymentService(LoanRepaymentProcessor processor,
                                IdempotentExecutor idempotentExecutor,
                                CurrentMemberProvider currentMember) {
        this.processor = processor;
        this.idempotentExecutor = idempotentExecutor;
        this.currentMember = currentMember;
    }

    /** 회차 상환 */
    public RepaymentResponse repay(Long loanId, Integer installmentNo, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(REPAY, memberId, loanId, installmentNo);

        return idempotentExecutor.run(idempotencyKey, memberId, hash, RepaymentResponse.class,
                () -> processor.repay(loanId, memberId, installmentNo, idempotencyKey));
    }

    /** 전액 중도상환 */
    public RepaymentResponse prepayAll(Long loanId, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(PREPAY, memberId, loanId);

        return idempotentExecutor.run(idempotencyKey, memberId, hash, RepaymentResponse.class,
                () -> processor.prepayAll(loanId, memberId, idempotencyKey));
    }
}
