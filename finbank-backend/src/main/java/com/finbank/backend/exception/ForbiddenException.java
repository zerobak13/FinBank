package com.finbank.backend.exception;

/**
 * 인증은 되었으나 해당 리소스에 접근할 권한이 없을 때 발생시킨다.
 * 예: 본인 소유가 아닌 계좌를 조회·입출금·이체하려는 경우.
 * GlobalExceptionHandler에서 403 FORBIDDEN으로 매핑된다.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
