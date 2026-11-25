package com.finbank.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 비즈니스 에러 코드. 각 코드가 자신의 HTTP 상태를 직접 가진다.
 *
 * <p>도입 배경: 예전에는 {@code BusinessException(String)}이 전부 400 BUSINESS_ERROR로
 * 매핑되어 409(상태 충돌)/422(검증 실패) 구분이 불가능했다. 코드 기반 생성자를 먼저 더하고(expand),
 * 문자열 예외를 모두 코드로 옮긴 뒤 옛 생성자를 지웠다(contract, 2026-11-22).
 * 이제 모든 비즈니스 예외는 코드를 가진다. 새 오류는 이 enum에 추가한다.</p>
 */
public enum ErrorCode {

    // ── 계좌/이체 (400) ──────────────────────────────────────────
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다."),
    SAME_ACCOUNT_TRANSFER(HttpStatus.BAD_REQUEST, "같은 계좌로는 이체할 수 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.BAD_REQUEST, "잠금된 계좌가 있습니다."),

    // ── 요청 형식 (400) ─────────────────────────────────────────
    INVALID_PAGE_REQUEST(HttpStatus.BAD_REQUEST, "페이지 번호나 크기가 올바르지 않습니다."),

    // ── 멱등키 형식 (400) ───────────────────────────────────────
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
    IDEMPOTENCY_KEY_TOO_LONG(HttpStatus.BAD_REQUEST, "Idempotency-Key는 64자 이하여야 합니다."),

    // ── 인증 실패 (401) ─────────────────────────────────────────
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh Token이 만료되었습니다. 다시 로그인해주세요."),

    // ── 회원 (409) ─────────────────────────────────────────────
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),

    // ── 상태 충돌 (409) — 여신 모듈 ────────────────────────────────
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
    ALREADY_EXECUTED(HttpStatus.CONFLICT, "이미 실행된 대출 신청입니다."),
    ALREADY_REPAID(HttpStatus.CONFLICT, "이미 상환된 회차입니다."),
    OUT_OF_ORDER_INSTALLMENT(HttpStatus.CONFLICT, "회차는 순서대로만 상환할 수 있습니다."),
    IDEMPOTENT_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "동일한 요청이 처리 중입니다."),

    // ── 검증 실패 (422) — 여신 모듈 ────────────────────────────────
    AMOUNT_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "허용 범위를 벗어난 금액입니다."),
    TERM_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "허용 범위를 벗어난 대출 기간입니다."),
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "같은 멱등키로 다른 내용의 요청을 보낼 수 없습니다."),

    // ── 일시적인 서버 사정 (503) — 요청은 맞고, 잠시 후 다시 하면 된다 ──
    ACCOUNT_NUMBER_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "계좌번호 생성에 실패했습니다. 잠시 후 다시 시도해주세요.");

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
