package com.finbank.backend.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 멱등성 보장 어노테이션.
 *
 * 이 어노테이션이 붙은 컨트롤러 메서드는 요청 시
 * Idempotency-Key 헤더(UUID v4)를 통해 중복 실행을 방지한다.
 *
 * 동작:
 *   - 헤더 없음 → 일반 요청으로 통과
 *   - 헤더 있음 + 처음 요청 → 정상 처리 후 응답 캐시
 *   - 헤더 있음 + 동일 키 재요청 → 캐시된 응답 즉시 반환 (DB 미실행)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
