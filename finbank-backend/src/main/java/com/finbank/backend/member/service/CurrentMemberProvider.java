package com.finbank.backend.member.service;

import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.member.repository.MemberRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 컨텍스트의 "현재 회원"을 꺼내는 단일 출처.
 *
 * <p>서비스마다 SecurityContextHolder를 직접 뒤지는 코드가 복사되면
 * 인증 방식이 바뀔 때 고쳐야 할 곳이 흩어진다. 여신 모듈은 이 컴포넌트만 쓴다.</p>
 */
@Component
public class CurrentMemberProvider {

    private final MemberRepository memberRepository;

    public CurrentMemberProvider(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public Member current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new NotFoundException("인증 정보를 찾을 수 없습니다.");
        }
        String email = authentication.getPrincipal().toString();
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Member not found: " + email));
    }

    /** 트랜잭션 밖(멱등 선점 단계)에서 식별자만 필요할 때 */
    public Long currentId() {
        return current().getId();
    }
}
