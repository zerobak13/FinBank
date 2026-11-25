package com.finbank.backend.config;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger 보안 이름 가드 — 컨트롤러의 {@code @SecurityRequirement} 이름이 등록한 보안 스킴 이름과 다르면
 * (대소문자까지) Swagger UI가 그 API에 토큰을 붙이지 않아 401이 난다. 실제로 대출 API 3곳이 그랬다.
 */
class SwaggerSecurityNameTest {

    @Test
    @DisplayName("모든 컨트롤러의 Swagger 보안 이름이 등록한 스킴 이름과 같다")
    void securityRequirementNamesMatchScheme() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> names = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.finbank.backend")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            Stream.concat(
                            Stream.of(controller.getAnnotationsByType(SecurityRequirement.class)),
                            Stream.of(controller.getMethods())
                                    .flatMap(m -> Stream.of(m.getAnnotationsByType(SecurityRequirement.class))))
                    .forEach(requirement -> names.add(controller.getSimpleName() + " → " + requirement.name()));
        }

        assertThat(names).hasSizeGreaterThanOrEqualTo(4)   // 계좌 + 대출 3개
                .allSatisfy(name -> assertThat(name).endsWith(" → " + SwaggerConfig.SECURITY_SCHEME_NAME));
    }
}
