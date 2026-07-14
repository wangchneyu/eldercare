package com.eldercare.common.core.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum SystemErrorCode implements IErrorCode {
    INTERNAL_ERROR(100001, "系统繁忙，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    BAD_REQUEST(100002, "请求参数不合法", HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED(100003, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(100004, "不支持的内容类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    TOO_MANY_REQUESTS(100005, "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
    SERVICE_UNAVAILABLE(100006, "服务暂不可用，请稍后再试", HttpStatus.SERVICE_UNAVAILABLE),
    REMOTE_CALL_FAILED(100007, "依赖服务暂不可用，请稍后再试", HttpStatus.BAD_GATEWAY),
    UNAUTHORIZED(110001, "登录状态已失效，请重新登录", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(110002, "无权执行此操作", HttpStatus.FORBIDDEN);

    private final int code;
    private final String msg;
    private final HttpStatus httpStatus;
}