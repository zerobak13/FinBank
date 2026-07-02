package com.finbank.backend.repository;

import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Refresh Token(refresh_tokens) 테이블 접근 리포지토리.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 토큰 값으로 조회 (재발급·로그아웃 시 사용) */
    Optional<RefreshToken> findByToken(String token);

    /**
     * 재로그인 시 기존 토큰 즉시 삭제
     *
     * flushAutomatically = true : DELETE를 DB에 즉시 반영
     * clearAutomatically = true : 1차 캐시(영속성 컨텍스트) 초기화
     *
     * 이 설정이 없으면 JPA가 DELETE를 트랜잭션 커밋 시점까지 지연시켜
     * 같은 트랜잭션 내 INSERT 시 UNIQUE 제약 위반이 발생함
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken rt WHERE rt.member = :member")
    void deleteByMember(@Param("member") Member member);
}
