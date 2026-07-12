package com.finbank.backend.exception;

import com.finbank.backend.dto.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorCode 기반 예외 매핑 검증 (순수 단위 테스트 — Spring 컨텍스트/DB 불필요).
 * 레거시(문자열) 예외의 기존 동작이 유지되는지도 함께 확인한다(점진 이관의 안전망).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("코드 기반 예외는 코드가 가진 HTTP 상태와 코드명으로 매핑된다")
    void errorCode_exception_maps_to_its_status() {
        ResponseEntity<ApiError> res =
                handler.handleBusiness(new BusinessException(ErrorCode.INVALID_STATE));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().getCode()).isEqualTo("INVALID_STATE");
        assertThat(res.getBody().getMessage()).isEqualTo(ErrorCode.INVALID_STATE.getDefaultMessage());
    }

    @Test
    @DisplayName("코드 + 커스텀 메시지 생성자는 메시지를 덮어쓴다")
    void errorCode_with_custom_message() {
        ResponseEntity<ApiError> res =
                handler.handleBusiness(new BusinessException(ErrorCode.AMOUNT_OUT_OF_RANGE, "최대 한도는 5,000만원입니다."));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().getCode()).isEqualTo("AMOUNT_OUT_OF_RANGE");
        assertThat(res.getBody().getMessage()).isEqualTo("최대 한도는 5,000만원입니다.");
    }

    @Test
    @DisplayName("레거시 문자열 예외는 기존대로 400 BUSINESS_ERROR로 매핑된다")
    void legacy_message_exception_maps_to_400() {
        ResponseEntity<ApiError> res =
                handler.handleBusiness(new BusinessException("잔액이 부족합니다."));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("BUSINESS_ERROR");
    }
}
