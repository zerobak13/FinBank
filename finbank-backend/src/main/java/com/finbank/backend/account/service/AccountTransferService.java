package com.finbank.backend.account.service;

import com.finbank.backend.account.dto.TransferRequest;
import com.finbank.backend.account.dto.TransferResponse;
import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.common.idempotency.RequestHash;
import com.finbank.backend.member.service.CurrentMemberProvider;
import org.springframework.stereotype.Service;

/**
 * 이체 API의 진입점 — 멱등 처리를 감싸고 실제 이체는 {@link AccountService}에 위임한다.
 *
 * <p>대출 상환({@code LoanRepaymentService})과 같은 구조다. 이 클래스에는 트랜잭션이 없다(의도적):
 * 키 선점/해제는 각자 별도 트랜잭션이고, 이체 본체는 자기 트랜잭션 안에서 응답을 확정한다.</p>
 *
 * <p>요청 해시에 출금 계좌·받는 계좌·금액을 모두 넣는다. 같은 키로 금액만 바꿔 보내는 것은
 * 클라이언트 버그이며, 조용히 이전 응답을 돌려주면 사용자는 바꾼 금액이 이체됐다고 착각한다(→ 422).
 * 금액은 {@code stripTrailingZeros}로 정규화해 {@code 10000}과 {@code 10000.00}을 같은 요청으로 본다.</p>
 */
@Service
public class AccountTransferService {

    private static final String TRANSFER = "account-transfer";

    private final AccountService accountService;
    private final IdempotentExecutor idempotentExecutor;
    private final CurrentMemberProvider currentMember;

    public AccountTransferService(AccountService accountService,
                                  IdempotentExecutor idempotentExecutor,
                                  CurrentMemberProvider currentMember) {
        this.accountService = accountService;
        this.idempotentExecutor = idempotentExecutor;
        this.currentMember = currentMember;
    }

    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String amount = request.getAmount() == null
                ? null
                : request.getAmount().stripTrailingZeros().toPlainString();
        String hash = RequestHash.of(TRANSFER, memberId,
                request.getFromAccountId(), request.getToAccountNumber(), amount);

        return idempotentExecutor.run(idempotencyKey, memberId, hash, TransferResponse.class,
                () -> accountService.transfer(request, idempotencyKey));
    }
}
