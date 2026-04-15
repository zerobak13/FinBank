package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refresh Token 엔티티
 *
 * JWT Access Token (1시간) 만료 후 재발급을 위해 사용하는 불투명 토큰(UUID).
 * JWT 방식의 refresh token은 탈취 시 만료 전까지 막을 수 없지만,
 * DB 저장 방식은 즉시 폐기(logout)가 가능하여 금융 서비스에 더 적합합니다.
 *
 * Token Rotation 정책:
 * - /api/auth/refresh 호출 시 기존 토큰 삭제 + 새 토큰 발급
 * - 탈취된 토큰으로 재사용 시도 시 이미 삭제되어 있어 방어 가능
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 회원 1명당 토큰 1개 유지 (재로그인 시 덮어씀)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Column(nullable = false, unique = true, length = 36)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public static RefreshToken create(Member member, long expirationMs) {
        RefreshToken rt = new RefreshToken();
        rt.member = member;
        rt.token = UUID.randomUUID().toString();
        rt.expiresAt = LocalDateTime.now().plusSeconds(expirationMs / 1000);
        return rt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}
