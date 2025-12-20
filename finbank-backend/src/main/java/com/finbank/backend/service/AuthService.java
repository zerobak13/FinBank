package com.finbank.backend.service;

import com.finbank.backend.config.JwtTokenProvider;
import com.finbank.backend.domain.Member;
import com.finbank.backend.dto.AuthResponse;
import com.finbank.backend.dto.LoginRequest;
import com.finbank.backend.dto.RegisterRequest;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.repository.MemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(MemberRepository memberRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public AuthResponse register(RegisterRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("이미 가입된 이메일입니다.");
        }

        String hash = passwordEncoder.encode(request.getPassword());
        Member member = new Member(request.getEmail(), request.getName(), hash);
        memberRepository.save(member);

        String token = jwtTokenProvider.createToken(member.getEmail());
        return new AuthResponse(token, member.getEmail(), member.getName());
    }


    public AuthResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String token = jwtTokenProvider.createToken(member.getEmail());
        return new AuthResponse(token, member.getEmail(), member.getName());
    }
}
