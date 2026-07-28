package com.finbank.backend.loan.service.review;

import java.util.Optional;

/**
 * 자동심사 룰 인터페이스.
 *
 * <p>룰 하나 = 구현 클래스 하나. 스프링이 {@code List<LoanReviewer>}로 전부 주입하므로
 * 룰 추가는 클래스 추가만으로 끝난다(서비스 코드 무수정 — OCP).
 * 순서는 {@code @Order}로 제어하며, 첫 탈락 룰의 코드가 reject_reason에 기록된다.</p>
 */
public interface LoanReviewer {

    /**
     * 심사 수행.
     *
     * @return 탈락이면 룰 코드(Optional에 담아), 통과면 {@code Optional.empty()}
     */
    Optional<String> reject(ReviewContext context);
}
