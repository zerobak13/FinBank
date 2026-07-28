package com.finbank.backend.loan.service;

import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.common.idempotency.RequestHash;
import com.finbank.backend.loan.dto.LoanExecutionResponse;
import com.finbank.backend.member.service.CurrentMemberProvider;
import org.springframework.stereotype.Service;

/**
 * 대출 실행 API의 진입점 — 멱등 처리를 감싸고, 실제 처리는
 * {@link LoanExecutionProcessor}(트랜잭션 본체)에 위임한다.
 *
 * <p><b>왜 클래스를 나눴나</b>: 멱등키 선점/해제는 본 처리와 <b>다른 트랜잭션</b>이어야 한다.
 * 한 클래스 안에서 자기 메서드를 호출하면 프록시를 타지 않아 트랜잭션 경계가 적용되지 않으므로,
 * "경계가 다른 두 책임"을 두 빈으로 분리했다.</p>
 */
@Service
public class LoanExecutionService {

    private static final String OPERATION = "loan-execute";

    private final LoanExecutionProcessor processor;
    private final IdempotentExecutor idempotentExecutor;
    private final CurrentMemberProvider currentMember;

    public LoanExecutionService(LoanExecutionProcessor processor,
                                IdempotentExecutor idempotentExecutor,
                                CurrentMemberProvider currentMember) {
        this.processor = processor;
        this.idempotentExecutor = idempotentExecutor;
        this.currentMember = currentMember;
    }

    public LoanExecutionResponse execute(Long applicationId, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(OPERATION, memberId, applicationId);

        return idempotentExecutor.run(idempotencyKey, memberId, hash, LoanExecutionResponse.class,
                () -> processor.execute(applicationId, memberId, idempotencyKey));
    }
}
