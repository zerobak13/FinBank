package com.finbank.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Access Token 재발급/로그아웃 요청 바디 (POST /api/auth/refresh, /logout).
 * 발급받은 Refresh Token을 담는다.
 */
@Schema(description = "Access Token 재발급 요청")
public class TokenRefreshRequest {

    @Schema(description = "발급받은 Refresh Token (UUID 형식)", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotBlank
    private String refreshToken;

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
