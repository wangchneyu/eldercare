package com.eldercare.common.core.exception;

import com.eldercare.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException e) {
        IErrorCode code = e.getErrorCode();
        log.warn("业务异常: code={}, msg={}", code.getCode(), code.getMsg());
        // 强制使用枚举中定义的 HTTP 状态码
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("系统未知异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(SystemErrorCode.INTERNAL_ERROR));
    }
}