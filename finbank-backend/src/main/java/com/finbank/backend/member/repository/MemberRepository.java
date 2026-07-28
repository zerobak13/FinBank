package com.finbank.backend.member.repository;

import com.finbank.backend.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 회원(members) 테이블 접근 리포지토리.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /** 이메일로 회원 조회 (로그인·인증 컨텍스트에서 사용) */
    Optional<Member> findByEmail(String email);

    /** 이메일 중복 여부 (회원가입 시 검증) */
    boolean existsByEmail(String email);
}
