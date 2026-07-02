package com.finbank.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false, length = 255)
    private String requestPath;

    // 0 = 처리중, 나머지 = HTTP 상태코드 (완료)
    @Column(nullable = false)
    private int responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public static IdempotencyKey of(String key, Member member, String requestPath) {
        IdempotencyKey ik = new IdempotencyKey();
        ik.idempotencyKey = key;
        ik.member = member;
        ik.requestPath = requestPath;
        ik.responseStatus = 0; // 처리중
        ik.createdAt = LocalDateTime.now();
        ik.expiresAt = LocalDateTime.now().plusHours(24);
        return ik;
    }

    /** 처리 완료 후 응답 저장 */
    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    public boolean isCompleted() {
        return this.responseStatus != 0;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}
