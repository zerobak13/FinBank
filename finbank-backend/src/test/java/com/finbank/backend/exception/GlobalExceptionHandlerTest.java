package com.finbank.backend.exception;

import com.finbank.backend.exception.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorCode 기반 예외 매핑 검증 (순수 단위 테스트 — Spring 컨텍스트/DB 불필요).
 * 모든 비즈니스 예외는 코드를 가지며, HTTP 상태와 코드명은 코드가 정한다.
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
    @DisplayName("인증 실패는 401, 이메일 중복은 409, 서버 쪽 일시 장애는 503 — 문자열 예외 시절의 400 일괄 처리가 없다")
    void auth_conflict_and_unavailable_codes_map_to_their_status() {
        ResponseEntity<ApiError> login = handler.handleBusiness(new BusinessException(ErrorCode.LOGIN_FAILED));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody().getCode()).isEqualTo("LOGIN_FAILED");

        ResponseEntity<ApiError> duplicate = handler.handleBusiness(new BusinessException(ErrorCode.DUPLICATE_EMAIL));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<ApiError> numbering =
                handler.handleBusiness(new BusinessException(ErrorCode.ACCOUNT_NUMBER_GENERATION_FAILED));
        assertThat(numbering.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
