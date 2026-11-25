package com.finbank.backend.exception;

import com.finbank.backend.exception.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;

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

    @Test
    @DisplayName("요청이 잘못된 경우는 500이 아니다 — 없는 주소 404, 지원하지 않는 요청 방식 405, 빠진 파라미터 400")
    void framework_client_errors_are_not_500() {
        ResponseEntity<ApiError> notFound =
                handler.handleException(new NoResourceFoundException(HttpMethod.GET, "api/nope"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().getCode()).isEqualTo("HTTP_404");

        assertThat(handler.handleException(new HttpRequestMethodNotSupportedException("PUT")).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        ResponseEntity<ApiError> missing = handler.handleMissingParameter(
                new MissingServletRequestParameterException("amount", "BigDecimal"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().getMessage()).isEqualTo("amount 값이 필요합니다.");
    }

    @Test
    @DisplayName("DB 제약 위반: UNIQUE 위반(1062)만 409 중복 요청, 길이 초과 등은 '중복'으로 가리지 않고 500")
    void dataIntegrity_onlyDuplicateKeyIsConflict() {
        ResponseEntity<ApiError> duplicate = handler.handleDataIntegrity(new DataIntegrityViolationException("dup",
                new SQLException("Duplicate entry 'k' for key 'uq_idem_key'", "23000", 1062)));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().getCode()).isEqualTo("DUPLICATE_REQUEST");

        ResponseEntity<ApiError> tooLong = handler.handleDataIntegrity(new DataIntegrityViolationException("too long",
                new SQLException("Data too long for column 'response_body'", "22001", 1406)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(tooLong.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("읽을 수 없는 본문·형식이 틀린 경로 값은 400, 예상하지 못한 예외만 500")
    void unreadable_body_and_type_mismatch_are_400() {
        assertThat(handler.handleUnreadableBody(new HttpMessageNotReadableException(
                "깨진 JSON", new MockHttpInputMessage(new byte[0]))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<ApiError> mismatch = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null));
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody().getMessage()).isEqualTo("id 값의 형식이 올바르지 않습니다.");

        assertThat(handler.handleException(new IllegalStateException("예상하지 못한 오류")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
