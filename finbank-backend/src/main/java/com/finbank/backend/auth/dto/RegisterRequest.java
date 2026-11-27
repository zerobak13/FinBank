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

    // 길이 상한은 DB 칸과 같다 — 넘는 값이 DB까지 가면 제약 위반이 되므로 요청 단계에서 400으로 막는다
    @Schema(description = "이메일 주소 (100자 이하)", example = "user@example.com")
    @Email
    @NotBlank
    @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
    private String email;

    @Schema(description = "이름 (50자 이하)", example = "박제영")
    @NotBlank
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    // BCrypt는 72바이트까지만 쓰므로 상한도 72
    @Schema(description = "비밀번호 (8~72자)", example = "password123")
    @NotBlank
    @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
