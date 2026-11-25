package com.finbank.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.sql.SQLException;

/**
 * 전역 예외 처리기. 서비스에서 던진 예외를 일관된 ApiError 응답과 알맞은 HTTP 상태로 바꾼다.
 *
 * <ul>
 *   <li>비즈니스 예외 — 예외가 가진 {@link ErrorCode}가 상태를 정한다 (400·401·409·422·503)</li>
 *   <li>404 NotFound · 403 Forbidden</li>
 *   <li>요청 자체가 잘못된 경우 — 검증 실패·헤더나 파라미터 누락·읽을 수 없는 본문·형식이 틀린 값은 400,
 *       없는 주소는 404, 지원하지 않는 요청 방식은 405처럼 스프링이 정해 둔 상태 그대로</li>
 *   <li>그 밖의 예상하지 못한 예외만 500 (로그에 스택 트레이스)</li>
 * </ul>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        ApiError error = new ApiError(ex.getMessage(), "NOT_FOUND");
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex) {
        ApiError error = new ApiError(ex.getMessage(), "FORBIDDEN");
        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        // 상태와 코드명은 예외가 가진 ErrorCode가 정한다
        ErrorCode code = ex.getErrorCode();
        ApiError error = new ApiError(ex.getMessage(), code.name());
        return new ResponseEntity<>(error, code.getStatus());
    }

    /**
     * 필수 헤더 누락 — 대표적으로 Idempotency-Key.
     * 이 핸들러가 없으면 아래 catch-all(Exception)이 잡아 500으로 나간다. 클라이언트 잘못은 400이어야 한다.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        ApiError error = new ApiError(ex.getHeaderName() + " 헤더가 필요합니다.", "MISSING_HEADER");
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * DB 제약 위반.
     *
     * <ul>
     *   <li>UNIQUE 위반(MySQL 1062) — 멱등키·이중 실행의 <b>DB 레벨 최종 방어선</b>이 작동한 경우. 409(충돌)로 알린다.</li>
     *   <li>그 밖(길이 초과·FK 위반 등) — 입력 검증을 통과했는데 DB가 거절했다면 서버 쪽 결함이다.
     *       예전처럼 전부 "중복된 요청"으로 답하면 원인이 가려진다(실행 응답이 칸을 넘던 버그도 그렇게 보였다). 500으로 남긴다.</li>
     * </ul>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        if (isDuplicateKey(ex)) {
            log.warn("UNIQUE 제약 위반 (중복 요청으로 추정)", ex);
            return new ResponseEntity<>(new ApiError("중복된 요청입니다.", "DUPLICATE_REQUEST"), HttpStatus.CONFLICT);
        }
        log.error("DB 제약 위반", ex);
        return new ResponseEntity<>(new ApiError("Internal server error", "INTERNAL_ERROR"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** MySQL 1062(Duplicate entry)만 중복으로 본다 — 원인 사슬을 따라가며 확인 */
    private static boolean isDuplicateKey(Throwable ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }

    /** 필수 쿼리 파라미터 누락 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        ApiError error = new ApiError(ex.getParameterName() + " 값이 필요합니다.", "MISSING_PARAMETER");
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /** 읽을 수 없는 요청 본문 — 깨진 JSON, 숫자 자리에 문자 등 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        ApiError error = new ApiError("요청 본문을 읽을 수 없습니다. JSON 형식과 값의 타입을 확인해 주세요.",
                "INVALID_REQUEST_BODY");
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /** 경로·쿼리 값의 형식이 틀림 — 예: 계좌 ID 자리에 문자 ({@code /api/accounts/abc}) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ApiError error = new ApiError(ex.getName() + " 값의 형식이 올바르지 않습니다.", "INVALID_PARAMETER");
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        ApiError error = new ApiError(msg, "VALIDATION_ERROR");
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception ex) {
        // 스프링이 상태를 정해 둔 요청 오류(없는 주소 404, 지원하지 않는 요청 방식 405 등)는 그 상태 그대로.
        // 스프링 부트 3.2부터 없는 주소는 NoResourceFoundException으로 여기까지 오므로, 이 분기가 없으면 500이 된다.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            return new ResponseEntity<>(new ApiError(clientErrorMessage(status), "HTTP_" + status.value()), status);
        }
        log.error("처리되지 않은 예외 발생", ex);
        ApiError error = new ApiError("Internal server error", "INTERNAL_ERROR");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static String clientErrorMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "요청한 주소를 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 방식입니다.";
            case 415 -> "지원하지 않는 본문 형식입니다.";
            default -> "잘못된 요청입니다.";
        };
    }
}
