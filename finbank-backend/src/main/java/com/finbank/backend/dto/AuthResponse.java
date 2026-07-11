package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인증 응답. 로그인/회원가입/토큰 재발급 성공 시 반환되며
 * Access Token, Refresh Token과 사용자 기본 정보(이메일/이름)를 담는다.
 */
@Schema(description = "인증 응답 (로그인/회원가입/토큰 재발급 성공 시 반환)")
public class AuthResponse {

    @Schema(description = "JWT Access Token (1시간 유효). Authorize 버튼에 입력하세요.", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "Refresh Token (7일 유효). Access Token 만료 시 /api/auth/refresh 에 사용합니다.", example = "550e8400-e29b-41d4-a716-446655440000")
    private String refreshToken;

    @Schema(description = "사용자 이메일", example = "user@example.com")
    private String email;

    @Schema(description = "사용자 이름", example = "박제영")
    private String name;

    public AuthResponse(String accessToken, String refreshToken, String email, String name) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.email = email;
        this.name = name;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getEmail() { return email; }
    public String getName() { return name; }

    // 하위 호환: 프론트엔드가 "token" 키로 받는 경우를 위한 alias
    public String getToken() { return accessToken; }
}
