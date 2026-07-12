package com.finbank.backend.exception;

import java.time.LocalDateTime;

/**
 * API 공통 에러 응답. 예외 발생 시 GlobalExceptionHandler가 이 형태로 반환한다.
 */
public class ApiError {

    /** 사용자에게 보여줄 에러 메시지 */
    private String message;
    /** 에러 분류 코드 (NOT_FOUND / FORBIDDEN / BUSINESS_ERROR / VALIDATION_ERROR / INTERNAL_ERROR) */
    private String code;
    /** 에러 발생 시각 */
    private LocalDateTime timestamp = LocalDateTime.now();

    public ApiError(String message, String code) {
        this.message = message;
        this.code = code;
    }

    public String getMessage() { return message; }
    public String getCode() { return code; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
