package com.finbank.backend.exception;

/**
 * 요청한 리소스를 찾을 수 없을 때 발생시킨다. (계좌·회원·받는 계좌 없음 등)
 * GlobalExceptionHandler에서 404 Not Found로 매핑된다.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
