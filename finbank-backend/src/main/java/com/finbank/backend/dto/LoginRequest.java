package com.finbank.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 바디 (POST /api/auth/login). 이메일/비밀번호를 받는다.
 */
@Schema(description = "로그인 요청")
public class LoginRequest {

    @Schema(description = "이메일 주소", example = "user@example.com")
    @Email
    @NotBlank
    private String email;

    @Schema(description = "비밀번호", example = "password123")
    @NotBlank
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
