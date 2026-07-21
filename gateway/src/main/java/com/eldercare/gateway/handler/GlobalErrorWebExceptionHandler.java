package com.eldercare.gateway.handler;

import com.eldercare.common.core.domain.R;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Gateway 全局错误处理器（WebFlux 响应式）
 * <p>
 * 继承 {@link AbstractErrorWebExceptionHandler}，覆盖所有未被 Sentinel 和 Filter 拦截的异常场景。
 * <p>
 * 严格对齐《错误码及异常设计》1.1 节 HTTP 状态与业务码映射表。
 * <p>
 * Sentinel 的 BlockException 由 SentinelGatewayConfig.sentinelBlockExceptionHandler()（@Order(-2)）
 * 独立处理，本处理器覆盖其余所有场景。
 */
@Slf4j
@Component
@Order(-1)
public class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalErrorWebExceptionHandler(ErrorAttributes errorAttributes,
                                           WebProperties webProperties,
                                           ApplicationContext applicationContext,
                                           ServerCodecConfigurer serverCodecConfigurer,
                                           ObjectMapper objectMapper) {
        super(errorAttributes, webProperties.getResources(), applicationContext);
        this.objectMapper = objectMapper;
        // 注册 HTTP 消息读写器
        super.setMessageReaders(serverCodecConfigurer.getReaders());
        super.setMessageWriters(serverCodecConfigurer.getWriters());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    /**
     * 统一错误响应：提取异常类型，映射 HTTP 状态和业务码，返回 R<T> JSON
     */
    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Throwable error = getError(request);

        // 根据异常类型确定 HTTP 状态和业务码
        ErrorMapping mapping = resolveErrorMapping(error);

        logError(mapping, error, request);

        R<Void> result = R.fail(mapping.errorCode);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            return ServerResponse.status(mapping.httpStatus)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(bytes));
        } catch (Exception e) {
            log.error("序列化错误响应失败", e);
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 按异常类型解析 HTTP 状态 + 业务码映射
     */
    private ErrorMapping resolveErrorMapping(Throwable error) {
        if (error == null) {
            return ErrorMapping.of(HttpStatus.INTERNAL_SERVER_ERROR, SystemErrorCode.INTERNAL_ERROR);
        }

        // 下游连接超时/拒绝
        if (error instanceof ConnectException || error instanceof TimeoutException
                || hasCauseOfType(error, ConnectException.class)
                || hasCauseOfType(error, TimeoutException.class)) {
            return ErrorMapping.of(HttpStatus.BAD_GATEWAY, SystemErrorCode.REMOTE_CALL_FAILED);
        }

        // 根据 HTTP 状态码映射（Spring 默认错误属性中的 status）
        Integer rawStatus = getRawStatusCode(error);
        if (rawStatus != null) {
            return mapHttpStatus(rawStatus);
        }

        return ErrorMapping.of(HttpStatus.INTERNAL_SERVER_ERROR, SystemErrorCode.INTERNAL_ERROR);
    }

    /**
     * 按 HTTP 状态码映射业务码
     */
    private ErrorMapping mapHttpStatus(int httpStatus) {
        return switch (httpStatus) {
            case 404 -> ErrorMapping.of(HttpStatus.NOT_FOUND, SystemErrorCode.NOT_FOUND);
            case 405 -> ErrorMapping.of(HttpStatus.METHOD_NOT_ALLOWED, SystemErrorCode.METHOD_NOT_ALLOWED);
            case 415 -> ErrorMapping.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, SystemErrorCode.UNSUPPORTED_MEDIA_TYPE);
            case 503 -> ErrorMapping.of(HttpStatus.SERVICE_UNAVAILABLE, SystemErrorCode.SERVICE_UNAVAILABLE);
            case 502 -> ErrorMapping.of(HttpStatus.BAD_GATEWAY, SystemErrorCode.REMOTE_CALL_FAILED);
            case 500 -> ErrorMapping.of(HttpStatus.INTERNAL_SERVER_ERROR, SystemErrorCode.INTERNAL_ERROR);
            default -> ErrorMapping.of(HttpStatus.valueOf(httpStatus), SystemErrorCode.INTERNAL_ERROR);
        };
    }

    /**
     * 尝试从 Spring 错误属性中提取原始 HTTP 状态码
     */
    private Integer getRawStatusCode(Throwable error) {
        try {
            // 尝试从 ResponseStatusException 中获取
            if (error instanceof org.springframework.web.server.ResponseStatusException rse) {
                return rse.getStatusCode().value();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 检查异常链中是否包含指定类型的 cause
     */
    private boolean hasCauseOfType(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isAssignableFrom(current.getClass())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 按文档《错误码及异常设计》三、记录的日志级别输出
     */
    private void logError(ErrorMapping mapping, Throwable error, ServerRequest request) {
        String path = request.uri().getPath();
        if (mapping.httpStatus.is5xxServerError()) {
            log.error("网关异常: path={}, httpStatus={}, code={}", path, mapping.httpStatus.value(),
                    mapping.errorCode.getCode(), error);
        } else if (mapping.httpStatus == HttpStatus.NOT_FOUND
                || mapping.httpStatus == HttpStatus.METHOD_NOT_ALLOWED
                || mapping.httpStatus == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            log.info("网关拒绝: path={}, httpStatus={}, code={}", path, mapping.httpStatus.value(),
                    mapping.errorCode.getCode());
        } else {
            log.warn("网关异常: path={}, httpStatus={}, code={}", path, mapping.httpStatus.value(),
                    mapping.errorCode.getCode(), error);
        }
    }

    /**
     * HTTP 状态 + 业务码映射内部类
     */
    private record ErrorMapping(HttpStatus httpStatus, SystemErrorCode errorCode) {
        static ErrorMapping of(HttpStatus httpStatus, SystemErrorCode errorCode) {
            return new ErrorMapping(httpStatus, errorCode);
        }
    }
}
