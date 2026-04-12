package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 응답 (로그인/회원가입 성공 시 반환)")
public class AuthResponse {

    @Schema(description = "JWT Access Token. Authorize 버튼에 입력하세요.", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;

    @Schema(description = "사용자 이메일", example = "user@example.com")
    private String email;

    @Schema(description = "사용자 이름", example = "박제영")
    private String name;

    public AuthResponse(String token, String email, String name) {
        this.token = token;
        this.email = email;
        this.name = name;
    }

    public String getToken() { return token; }
    public String getEmail() { return email; }
    public String getName() { return name; }
}
