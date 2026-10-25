package com.finbank.backend.loan.harness;

/**
 * 불변식 위반 1건 — "어떤 규칙이, 어디서, 어떻게" 깨졌는지.
 *
 * <p>검증 에이전트가 구현 에이전트에게 돌려보내는 실패 리포트의 한 줄이 된다.
 * 사람이 읽고 바로 재현할 수 있도록 실제 값을 detail에 담는다.</p>
 */
public record Violation(LoanInvariant invariant, String detail) {

    @Override
    public String toString() {
        return invariant.id() + " | " + detail + "  (" + invariant.description() + ")";
    }
}
