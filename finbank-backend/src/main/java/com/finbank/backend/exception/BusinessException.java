package com.finbank.backend.exception;

/**
 * 비즈니스 규칙 위반 예외. 잔액 부족, 잘못된 금액, 같은 계좌 이체, 잠금 계좌 등
 * "요청 자체가 규칙에 어긋난" 경우에 사용한다.
 *
 * <p>항상 {@link ErrorCode}를 가진다 — HTTP 상태와 코드명은 코드가 정하고, 서비스는 의미에 맞는 코드만 고른다.
 * 문자열만 받던 옛 생성자(400 BUSINESS_ERROR)는 모든 호출부를 코드로 옮긴 뒤 지웠다(2026-11-22).</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
