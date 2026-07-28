package com.finbank.backend.auth.controller;

import com.finbank.backend.auth.dto.AuthResponse;
import com.finbank.backend.auth.dto.LoginRequest;
import com.finbank.backend.auth.dto.RegisterRequest;
import com.finbank.backend.auth.dto.TokenRefreshRequest;
import com.finbank.backend.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 인증 API 컨트롤러. 회원가입/로그인/토큰 재발급/로그아웃 엔드포인트를 제공한다.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증 (Auth)", description = """
        회원가입, 로그인, 토큰 재발급 API.

        **토큰 구조**
        - Access Token (JWT): 1시간 유효. API 호출 시 Authorization 헤더에 사용.
        - Refresh Token (UUID): 7일 유효. DB 저장 방식으로 즉시 폐기 가능.

        **Token Rotation**: /refresh 호출 시 기존 Refresh Token을 폐기하고 새 토큰을 발급합니다.
        """)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "회원가입", description = "이메일, 이름, 비밀번호(4자 이상)로 회원가입합니다. Access Token과 Refresh Token을 함께 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원가입 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "이미 가입된 이메일",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인합니다. Access Token과 Refresh Token을 함께 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공"),
            @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치",
            content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(
            summary = "Access Token 재발급",
            description = """
                    Refresh Token으로 새 Access Token을 발급받습니다.

                    **Token Rotation 적용**: 호출 시 기존 Refresh Token은 즉시 폐기되고 새 Refresh Token이 발급됩니다.
                    탈취된 Refresh Token으로 재사용 시도 시 이미 삭제되어 있어 방어됩니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "새 Access Token + 새 Refresh Token 반환"),
            @ApiResponse(responseCode = "400", description = "유효하지 않거나 만료된 Refresh Token",
            content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @Operation(summary = "로그아웃", description = "Refresh Token을 서버에서 즉시 폐기합니다. 이후 해당 토큰으로 재발급 불가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그아웃 완료"),
            @ApiResponse(responseCode = "400", description = "Refresh Token 누락")
    })
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@Valid @RequestBody TokenRefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(Map.of("message", "로그아웃 완료"));
    }
}
