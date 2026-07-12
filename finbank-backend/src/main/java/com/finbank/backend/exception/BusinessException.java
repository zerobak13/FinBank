package com.finbank.backend.exception;

/**
 * 비즈니스 규칙 위반 예외. 잔액 부족, 잘못된 금액, 같은 계좌 이체, 잠금 계좌 등
 * "요청 자체가 규칙에 어긋난" 경우에 사용한다.
 *
 * <p>두 가지 생성 방식을 지원한다(점진 이관 전략):</p>
 * <ul>
 *   <li>{@code BusinessException(String)} — 레거시. 400 BUSINESS_ERROR로 매핑.</li>
 *   <li>{@code BusinessException(ErrorCode)} — 코드가 가진 HTTP 상태와 코드명으로 매핑.
 *       상태코드 세분화(409/422)가 필요한 새 코드는 이 방식을 쓴다.</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    /** 코드 기반 예외의 에러 코드. 레거시(문자열) 생성 시 null. */
    private final ErrorCode errorCode;

    public BusinessException(String message) {
        super(message);
        this.errorCode = null;
    }

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
