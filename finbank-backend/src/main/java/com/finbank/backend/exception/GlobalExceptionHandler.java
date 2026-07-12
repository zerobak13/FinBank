package com.finbank.backend.exception;

import com.finbank.backend.exception.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 전역 예외 처리기. 서비스에서 던진 예외를 일관된 ApiError 응답과 적절한 HTTP 상태코드로 변환한다.
 * 404(NotFound) / 403(Forbidden) / 400(Business·Validation) / 500(그 외)로 매핑한다.
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
        // 코드 기반 예외는 코드가 가진 상태/이름으로, 레거시(문자열) 예외는 기존처럼 400 BUSINESS_ERROR로.
        if (ex.getErrorCode() != null) {
            ErrorCode code = ex.getErrorCode();
            ApiError error = new ApiError(ex.getMessage(), code.name());
            return new ResponseEntity<>(error, code.getStatus());
        }
        ApiError error = new ApiError(ex.getMessage(), "BUSINESS_ERROR");
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
        log.error("처리되지 않은 예외 발생", ex);
        ApiError error = new ApiError("Internal server error", "INTERNAL_ERROR");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
