package com.finbank.backend.loan.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.loan.domain.LoanApplication;
import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import com.finbank.backend.loan.dto.LoanApplicationResponse;
import com.finbank.backend.loan.dto.LoanApplyRequest;
import com.finbank.backend.loan.dto.LoanProductResponse;
import com.finbank.backend.loan.repository.LoanApplicationRepository;
import com.finbank.backend.loan.repository.LoanProductRepository;
import com.finbank.backend.loan.service.review.LoanReviewer;
import com.finbank.backend.loan.service.review.ReviewContext;
import com.finbank.backend.member.repository.MemberRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 대출 신청·자동심사 서비스.
 *
 * <p>신청은 저장 즉시 자동심사를 거쳐 APPROVED/REJECTED로 확정된다.
 * 심사 룰은 {@link LoanReviewer} 구현체 목록으로 주입되며(@Order 순서),
 * 첫 탈락 룰의 코드가 reject_reason에 기록된다 — 룰 추가는 클래스 추가만으로 끝난다.</p>
 *
 * <p><b>계좌 검증과 심사의 경계</b>: 입금 계좌의 존재·소유권·잠금 여부는 심사 룰이 아니라
 * 입력 검증으로 처리한다(404/403/400). 잘못된 계좌를 고른 것은 "심사에서 떨어질 일"이 아니라
 * 애초에 성립하지 않는 요청이기 때문이다. 심사 룰은 계좌가 유효함을 전제로
 * <b>신용 판단</b>(한도·연체)만 담당한다.</p>
 */
@Service
public class LoanApplicationService {

    private final MemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final LoanProductRepository loanProductRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final List<LoanReviewer> reviewers; // @Order 순서대로 주입됨

    public LoanApplicationService(MemberRepository memberRepository,
                                  AccountRepository accountRepository,
                                  LoanProductRepository loanProductRepository,
                                  LoanApplicationRepository loanApplicationRepository,
                                  List<LoanReviewer> reviewers) {
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.loanProductRepository = loanProductRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.reviewers = reviewers;
    }

    /** 판매 중인 상품 목록 */
    @Transactional(readOnly = true)
    public List<LoanProductResponse> getProducts() {
        return loanProductRepository.findByStatus(ProductStatus.ON_SALE).stream()
                .map(LoanProductResponse::from)
                .toList();
    }

    /**
     * 대출 신청 + 즉시 자동심사.
     * 검증(상품 판매 여부, 금액/기간 범위) → 신청 저장(APPLIED) → 룰 순회 → 승인/탈락 확정.
     */
    @Transactional
    public LoanApplicationResponse apply(LoanApplyRequest request) {
        Member member = getCurrentMember();

        LoanProduct product = loanProductRepository.findById(request.productId())
                .filter(LoanProduct::isOnSale)
                .orElseThrow(() -> new NotFoundException("판매 중인 상품이 아닙니다: " + request.productId()));

        if (!product.isAmountInRange(request.requestedAmount())) {
            throw new BusinessException(ErrorCode.AMOUNT_OUT_OF_RANGE,
                    "신청 금액은 " + product.getMinAmount().toBigInteger() + "원 ~ "
                            + product.getMaxAmount().toBigInteger() + "원 사이여야 합니다.");
        }
        if (!product.isTermAllowed(request.termMonths())) {
            throw new BusinessException(ErrorCode.TERM_OUT_OF_RANGE,
                    "대출 기간은 1 ~ " + product.getMaxTermMonths() + "개월 사이여야 합니다.");
        }

        Account linkedAccount = resolveLinkedAccount(request.accountId(), member);

        LoanApplication application = loanApplicationRepository.save(
                new LoanApplication(member, product, linkedAccount,
                        request.requestedAmount(), request.termMonths()));

        // 자동심사: 첫 탈락 룰에서 즉시 확정
        ReviewContext context = new ReviewContext(member, product, request.requestedAmount());
        Optional<String> rejection = reviewers.stream()
                .map(rule -> rule.reject(context))
                .flatMap(Optional::stream)
                .findFirst();

        if (rejection.isPresent()) {
            application.reject(rejection.get());
        } else {
            application.approve();
        }

        return LoanApplicationResponse.from(application);
    }

    /** 내 신청 이력 (최신순) */
    @Transactional(readOnly = true)
    public List<LoanApplicationResponse> getMyApplications() {
        Member member = getCurrentMember();
        return loanApplicationRepository.findByMemberOrderByAppliedAtDesc(member).stream()
                .map(LoanApplicationResponse::from)
                .toList();
    }

    /** 신청 단건 조회 (본인 것만) */
    @Transactional(readOnly = true)
    public LoanApplicationResponse getApplication(Long id) {
        return LoanApplicationResponse.from(getOwnApplication(id));
    }

    /** 신청 취소 (본인 것만, 실행 전만 — 전이 검증은 도메인이 수행) */
    @Transactional
    public void cancel(Long id) {
        getOwnApplication(id).cancel();
    }

    /**
     * 입금받을 계좌 확정 — 남의 계좌를 지정하는 것은 403, 잠긴 계좌는 400으로 막는다.
     *
     * <p>실행 시점이 아니라 <b>신청 시점</b>에 검증하는 이유: 심사를 통과하고 나서야
     * "그 계좌는 쓸 수 없다"고 알리면 사용자는 이유를 알 수 없는 실패를 겪는다.
     * 입력 검증은 입력을 받는 자리에서 끝내는 것이 맞다.</p>
     */
    private Account resolveLinkedAccount(Long accountId, Member member) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌로만 대출을 신청할 수 있습니다.");
        }
        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                    "잠금된 계좌로는 대출을 신청할 수 없습니다.");
        }
        return account;
    }

    private LoanApplication getOwnApplication(Long id) {
        Member member = getCurrentMember();
        LoanApplication application = loanApplicationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("대출 신청을 찾을 수 없습니다: " + id));
        if (!application.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인의 대출 신청만 조회할 수 있습니다.");
        }
        return application;
    }

    private Member getCurrentMember() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email = principal.toString();
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Member not found: " + email));
    }
}
