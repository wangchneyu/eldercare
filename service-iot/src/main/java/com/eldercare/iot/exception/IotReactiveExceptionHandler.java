package com.eldercare.iot.exception;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.IErrorCode;
import com.eldercare.common.core.exception.SystemErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;
import java.util.Map;

/**
 * WebFlux-local exception mapping. The shared MVC advice is deliberately not scanned.
 */
@Slf4j
@RestControllerAdvice
public class IotReactiveExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException exception) {
        IErrorCode errorCode = exception.getErrorCode();
        log.warn("Business exception: code={}, msg={}", errorCode.getCode(), errorCode.getMsg());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(R.fail(errorCode));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<R<List<Map<String, String>>>> handleValidation(WebExchangeBindException exception) {
        List<Map<String, String>> errors = exception.getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(SystemErrorCode.BAD_REQUEST, errors));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<R<Void>> handleInput(ServerWebInputException exception) {
        log.warn("Invalid request input: {}", exception.getReason());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(SystemErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception exception) {
        log.error("Unhandled IoT service exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(SystemErrorCode.INTERNAL_ERROR));
    }

    private Map<String, String> toFieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "reason", error.getDefaultMessage() == null ? "Validation failed" : error.getDefaultMessage(),
                "rejectedValue", error.getRejectedValue() == null ? "null" : String.valueOf(error.getRejectedValue())
        );
    }
}
