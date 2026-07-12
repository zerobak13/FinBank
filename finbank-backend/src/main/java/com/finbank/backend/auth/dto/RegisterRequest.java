package com.finbank.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 바디 (POST /api/auth/register). 이메일/이름/비밀번호를 받는다.
 */
@Schema(description = "회원가입 요청")
public class RegisterRequest {

    @Schema(description = "이메일 주소", example = "user@example.com")
    @Email
    @NotBlank
    private String email;

    @Schema(description = "이름", example = "박제영")
    @NotBlank
    private String name;

    @Schema(description = "비밀번호 (4자 이상)", example = "password123")
    @NotBlank
    @Size(min = 4, max = 100)
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
