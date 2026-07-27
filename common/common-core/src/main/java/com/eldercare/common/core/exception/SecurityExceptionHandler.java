package com.eldercare.common.core.exception;

import com.eldercare.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 越权访问异常处理器（仅在 spring-security-core 存在时生效）
 * <p>
 * 使用 {@code @ConditionalOnClass} 保护，避免未引入 spring-security 的下游服务
 * 因 ClassNotFoundException 启动失败。
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("越权访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.fail(SystemErrorCode.FORBIDDEN));
    }
}
