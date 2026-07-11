package com.finbank.backend.exception;

/**
 * 비즈니스 규칙 위반 예외. 잔액 부족, 잘못된 금액, 같은 계좌 이체, 잠금 계좌 등
 * "요청 자체가 규칙에 어긋난" 경우에 사용한다. GlobalExceptionHandler에서 400 Bad Request로 매핑된다.
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
