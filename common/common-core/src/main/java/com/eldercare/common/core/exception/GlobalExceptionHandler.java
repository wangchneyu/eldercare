package com.eldercare.common.core.exception;

import com.eldercare.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器 (MVC)
 * <p>
 * 对齐《错误码及异常设计》三、异常分类表
 * <p>
 * 覆盖: BizException / 参数校验 / 类型转换 / 方法/媒体类型不支持 / 远程调用 / 未知异常
 * <p>
 * 注意: 认证授权异常（AccessDeniedException 等）由 Gateway 过滤器和 common-security 拦截器处理，
 * 不在本处理器范围。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException e) {
        IErrorCode code = e.getErrorCode();
        log.warn("业务异常: code={}, msg={}", code.getCode(), code.getMsg());
        return ResponseEntity.status(code.getHttpStatus()).body(R.fail(code));
    }

    // ==================== 参数校验与类型转换 ====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(SystemErrorCode.BAD_REQUEST.getCode(), msg));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");
        log.warn("参数绑定失败: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(SystemErrorCode.BAD_REQUEST.getCode(), msg));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型转换失败: {}={}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(SystemErrorCode.BAD_REQUEST.getCode(),
                        "参数类型不匹配: " + e.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(SystemErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必需参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(SystemErrorCode.BAD_REQUEST));
    }

    // ==================== 请求方法与媒体类型 ====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.info("不支持的请求方法: {}; 支持: {}", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(SystemErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.info("不支持的内容类型: {}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(R.fail(SystemErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    // ==================== 远程调用 ====================

    @ExceptionHandler(RemoteCallException.class)
    public ResponseEntity<R<Void>> handleRemoteCall(RemoteCallException e) {
        log.warn("远程调用失败: service={}, path={}, httpStatus={}",
                e.getServiceName(), e.getRequestPath(), e.getHttpStatus(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(R.fail(SystemErrorCode.REMOTE_CALL_FAILED));
    }

    // ==================== 兜底 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("系统未知异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(SystemErrorCode.INTERNAL_ERROR));
    }
}
