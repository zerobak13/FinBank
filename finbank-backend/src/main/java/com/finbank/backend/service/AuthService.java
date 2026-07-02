package com.finbank.backend.service;

import com.finbank.backend.config.JwtTokenProvider;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.RefreshToken;
import com.finbank.backend.dto.AuthResponse;
import com.finbank.backend.dto.LoginRequest;
import com.finbank.backend.dto.RegisterRequest;
import com.finbank.backend.dto.TokenRefreshRequest;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final long refreshExpirationMs;

    public AuthService(
            MemberRepository memberRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs
    ) {
        this.memberRepository = memberRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("이미 가입된 이메일입니다.");
        }

        String hash = passwordEncoder.encode(request.getPassword());
        Member member = new Member(request.getEmail(), request.getName(), hash);
        memberRepository.save(member);

        return issueTokens(member);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return issueTokens(member);
    }

    /**
     * Access Token 재발급 (Token Rotation)
     *
     * Refresh Token을 사용할 때마다 새 Refresh Token을 발급하고 기존 토큰을 폐기합니다.
     * 탈취된 토큰으로 재사용 시도 시 이미 삭제되어 있어 방어 가능합니다.
     */
    @Transactional
    public AuthResponse refresh(TokenRefreshRequest request) {
        RefreshToken saved = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new BusinessException("유효하지 않은 Refresh Token입니다."));

        if (saved.isExpired()) {
            refreshTokenRepository.delete(saved);
            throw new BusinessException("Refresh Token이 만료되었습니다. 다시 로그인해주세요.");
        }

        Member member = saved.getMember();

        // Token Rotation: 기존 토큰 폐기 후 새 토큰 발급
        refreshTokenRepository.delete(saved);

        return issueTokens(member);
    }

    /**
     * 로그아웃: Refresh Token 즉시 폐기
     * DB 저장 방식이므로 서버에서 강제 무효화 가능 (JWT 방식 대비 장점)
     */
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(refreshTokenRepository::delete);
    }

    // ── 공통 토큰 발급 로직 ─────────────────────────────────────────────

    private AuthResponse issueTokens(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getEmail());
        RefreshToken refreshToken = createAndSaveRefreshToken(member);
        return new AuthResponse(accessToken, refreshToken.getToken(), member.getEmail(), member.getName());
    }

    private RefreshToken createAndSaveRefreshToken(Member member) {
        // 기존 토큰이 있으면 삭제 (재로그인 시 1인 1토큰 유지)
        refreshTokenRepository.deleteByMember(member);

        RefreshToken refreshToken = RefreshToken.create(member, refreshExpirationMs);
        return refreshTokenRepository.save(refreshToken);
    }
}
