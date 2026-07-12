package com.finbank.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 비즈니스 에러 코드. 각 코드가 자신의 HTTP 상태를 직접 가진다.
 *
 * <p>도입 배경: 기존에는 {@code BusinessException(String)}이 전부 400 BUSINESS_ERROR로
 * 매핑되어 409(상태 충돌)/422(검증 실패) 구분이 불가능했다. 여신 모듈은 상태 전이 위반(409),
 * 한도/범위 위반(422) 등 세분화된 상태코드가 필요하므로 코드 기반 예외 체계를 도입한다.</p>
 *
 * <p>기존 문자열 기반 예외는 그대로 동작한다(점진 이관). 새 코드는 이 enum에 추가한다.</p>
 */
public enum ErrorCode {

    // ── 계좌/이체 (400) ──────────────────────────────────────────
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다."),
    SAME_ACCOUNT_TRANSFER(HttpStatus.BAD_REQUEST, "같은 계좌로는 이체할 수 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.BAD_REQUEST, "잠금된 계좌가 있습니다."),

    // ── 상태 충돌 (409) — 여신 모듈에서 사용 예정 ───────────────────
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),

    // ── 검증 실패 (422) — 여신 모듈에서 사용 예정 ───────────────────
    AMOUNT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "허용 범위를 벗어난 금액입니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
