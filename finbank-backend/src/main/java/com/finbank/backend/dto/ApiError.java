package com.finbank.backend.dto;

import java.time.LocalDateTime;

public class ApiError {

    private String message;
    private String code;
    private LocalDateTime timestamp = LocalDateTime.now();

    public ApiError(String message, String code) {
        this.message = message;
        this.code = code;
    }

    public String getMessage() { return message; }
    public String getCode() { return code; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
