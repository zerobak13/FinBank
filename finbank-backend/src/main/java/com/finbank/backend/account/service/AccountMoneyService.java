package com.finbank.backend.account.service;

import com.finbank.backend.account.dto.AccountCreateRequest;
import com.finbank.backend.account.dto.AccountSummaryResponse;
import com.finbank.backend.account.dto.CashResponse;
import com.finbank.backend.account.dto.TransferRequest;
import com.finbank.backend.account.dto.TransferResponse;
import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.common.idempotency.RequestHash;
import com.finbank.backend.member.service.CurrentMemberProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 계좌에서 돈이 움직이는 요청(개설·입금·출금·이체)의 진입점 — 멱등 처리를 감싸고 실제 처리는 {@link AccountService}에 위임한다.
 *
 * <p>대출 실행·상환({@code LoanExecutionService}·{@code LoanRepaymentService})과 같은 구조다. 이 클래스에는 트랜잭션이 없다(의도적):
 * 키 선점/해제는 각자 별도 트랜잭션이고, 본체는 자기 트랜잭션 안에서 응답을 확정한다.
 * {@link AccountService}의 본체 메서드는 패키지 밖에 공개하지 않아서, 다른 코드가 멱등 처리를 건너뛰고 돈을 옮길 수 없다.</p>
 *
 * <p>요청 해시에 작업 이름·회원·계좌·금액을 모두 넣는다. 같은 키로 금액만 바꿔 보내는 것은 클라이언트 버그이며,
 * 조용히 이전 응답을 돌려주면 사용자는 바꾼 금액이 처리됐다고 착각한다(→ 422).
 * 금액은 {@code stripTrailingZeros}로 정규화해 {@code 10000}과 {@code 10000.00}을 같은 요청으로 본다.</p>
 */
@Service
public class AccountMoneyService {

    private static final String OPEN = "account-open";
    private static final String DEPOSIT = "account-deposit";
    private static final String WITHDRAW = "account-withdraw";
    private static final String TRANSFER = "account-transfer";

    private final AccountService accountService;
    private final IdempotentExecutor idempotentExecutor;
    private final CurrentMemberProvider currentMember;

    public AccountMoneyService(AccountService accountService,
                               IdempotentExecutor idempotentExecutor,
                               CurrentMemberProvider currentMember) {
        this.accountService = accountService;
        this.idempotentExecutor = idempotentExecutor;
        this.currentMember = currentMember;
    }

    /** 계좌 개설 — 초기 입금이 있으면 돈이 생기므로 입금과 같은 멱등 처리. 재시도에는 처음 만든 계좌를 그대로 돌려준다 */
    public AccountSummaryResponse open(AccountCreateRequest request, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(OPEN, memberId, normalize(request.getInitialDeposit()));
        return idempotentExecutor.run(idempotencyKey, memberId, hash, AccountSummaryResponse.class,
                () -> accountService.createAccount(request, idempotencyKey));
    }

    public CashResponse deposit(Long accountId, BigDecimal amount, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(DEPOSIT, memberId, accountId, normalize(amount));
        return idempotentExecutor.run(idempotencyKey, memberId, hash, CashResponse.class,
                () -> accountService.deposit(accountId, amount, idempotencyKey));
    }

    public CashResponse withdraw(Long accountId, BigDecimal amount, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(WITHDRAW, memberId, accountId, normalize(amount));
        return idempotentExecutor.run(idempotencyKey, memberId, hash, CashResponse.class,
                () -> accountService.withdraw(accountId, amount, idempotencyKey));
    }

    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        Long memberId = currentMember.currentId();
        String hash = RequestHash.of(TRANSFER, memberId,
                request.getFromAccountId(), request.getToAccountNumber(), normalize(request.getAmount()));
        return idempotentExecutor.run(idempotencyKey, memberId, hash, TransferResponse.class,
                () -> accountService.transfer(request, idempotencyKey));
    }

    private static String normalize(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
