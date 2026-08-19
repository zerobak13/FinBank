package com.finbank.backend.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 설정 — {@link JPAQueryFactory}를 스프링 빈으로 올리는 유일한 지점.
 *
 * <p>주입되는 EntityManager는 스프링이 만든 프록시라, 실제 조회 시점의 트랜잭션에 묶인
 * 진짜 EntityManager로 위임된다. 따라서 이 팩토리를 싱글턴으로 공유해도 안전하다.</p>
 */
@Configuration
public class QuerydslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}
