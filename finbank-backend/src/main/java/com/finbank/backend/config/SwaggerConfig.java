package com.finbank.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 설정. API 메타정보와 JWT Bearer 인증 스킴을 등록해
 * Swagger UI에서 Authorize로 토큰을 넣고 API를 테스트할 수 있게 한다.
 */
@Configuration
public class SwaggerConfig {

    /** 보안 스킴 이름 — 컨트롤러의 {@code @SecurityRequirement}도 이 상수를 쓴다 (대소문자까지 같아야 토큰이 붙는다) */
    public static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, jwtSecurityScheme())
                );
    }

    private Info apiInfo() {
        return new Info()
                .title("FinBank API")
                .description("""
                        금융 거래 무결성과 동시성 제어를 중심으로 설계한 백엔드 API입니다.

                        **인증 방법**
                        1. `/api/auth/login`으로 로그인해 응답의 `accessToken` 값을 복사합니다.
                        2. 오른쪽 위 **Authorize** 버튼을 눌러 그 값만 붙여 넣습니다 (`Bearer `는 자동으로 붙습니다).
                        """)
                .version("1.0.0")
                .contact(new Contact()
                        .name("FinBank")
                        .email("finbank@example.com")
                );
    }

    private SecurityScheme jwtSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization")
                .description("JWT Access Token을 입력하세요. 'Bearer ' 접두어는 자동으로 붙습니다.");
    }
}
