package com.finbank.backend.auth.service;

import com.finbank.backend.auth.domain.RefreshToken;
import com.finbank.backend.auth.dto.AuthResponse;
import com.finbank.backend.auth.dto.LoginRequest;
import com.finbank.backend.auth.dto.RegisterRequest;
import com.finbank.backend.auth.dto.TokenRefreshRequest;
import com.finbank.backend.auth.repository.RefreshTokenRepository;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import com.finbank.backend.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인증 통합 테스트 — 가입·로그인·재발급(Token Rotation)·로그아웃.
 *
 * <p>Refresh Token을 DB에 저장하는 이유가 "서버에서 바로 무효화"이므로, 한 번 쓴 토큰과 로그아웃한 토큰을
 * 다시 쓸 수 없는지를 확인한다.</p>
 */
@SpringBootTest
class AuthServiceTest {

    @Autowired AuthService authService;
    @Autowired MemberRepository memberRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired DatabaseCleaner cleaner;

    private static final String EMAIL = "auth@test.com";
    private static final String PASSWORD = "password1234";

    @BeforeEach
    void setUp() {
        cleaner.clean();
    }

    private AuthResponse register() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(EMAIL);
        request.setName("인증테스터");
        request.setPassword(PASSWORD);
        return authService.register(request);
    }

    private static LoginRequest loginWith(String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(EMAIL);
        request.setPassword(password);
        return request;
    }

    private static TokenRefreshRequest refreshWith(String refreshToken) {
        TokenRefreshRequest request = new TokenRefreshRequest();
        request.setRefreshToken(refreshToken);
        return request;
    }

    @Test
    @DisplayName("가입하면 바로 쓸 수 있는 토큰이 나오고, 비밀번호는 해시로만 저장된다")
    void register_issuesTokens_andStoresPasswordHash() {
        AuthResponse res = register();

        assertThat(res.getAccessToken()).isNotBlank();
        assertThat(res.getRefreshToken()).isNotBlank();
        assertThat(memberRepository.findByEmail(EMAIL).orElseThrow().getPassword()).isNotEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("이미 가입된 이메일은 409, 틀린 비밀번호는 401")
    void duplicateEmail_and_wrongPassword() {
        register();

        assertThatThrownBy(this::register)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
        assertThatThrownBy(() -> authService.login(loginWith("wrong-password")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("재발급(Rotation): 새 토큰이 나오고, 한 번 쓴 토큰은 다시 쓸 수 없다")
    void refresh_rotatesToken() {
        register();
        String first = authService.login(loginWith(PASSWORD)).getRefreshToken();

        AuthResponse renewed = authService.refresh(refreshWith(first));

        assertThat(renewed.getRefreshToken()).isNotEqualTo(first);
        assertThatThrownBy(() -> authService.refresh(refreshWith(first)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
        assertThat(authService.refresh(refreshWith(renewed.getRefreshToken())).getAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("로그아웃하면 그 Refresh Token으로 더는 재발급받을 수 없다")
    void logout_revokesToken() {
        String token = register().getRefreshToken();

        authService.logout(token);

        assertThatThrownBy(() -> authService.refresh(refreshWith(token)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("만료된 Refresh Token은 401 REFRESH_TOKEN_EXPIRED")
    void expiredToken_isRejected() {
        register();
        Member member = memberRepository.findByEmail(EMAIL).orElseThrow();
        refreshTokenRepository.deleteAll();
        RefreshToken expired = refreshTokenRepository.saveAndFlush(RefreshToken.create(member, -1000));

        assertThatThrownBy(() -> authService.refresh(refreshWith(expired.getToken())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_EXPIRED);
    }
}
