package com.eldercare.common.core.domain;

import com.eldercare.common.core.exception.IErrorCode;
import com.eldercare.common.core.utils.TraceContext;
import lombok.Data;
import java.io.Serializable;

@Data
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String msg;
    private T data;
    private String traceId;

    // ========== 成功响应 ==========

    public static <T> R<T> ok() {
        return result(null, 0, "success");
    }

    public static <T> R<T> ok(T data) {
        return result(data, 0, "success");
    }

    // ========== 失败响应 ==========

    public static <T> R<T> fail(String msg) {
        return result(null, -1, msg);
    }

    public static <T> R<T> fail(int code, String msg) {
        return result(null, code, msg);
    }

    public static <T> R<T> fail(IErrorCode errorCode) {
        return result(null, errorCode.getCode(), errorCode.getMsg());
    }

    private static <T> R<T> result(T data, int code, String msg) {
        R<T> result = new R<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        // 严格落实规范：强制获取并填充 traceId
        result.setTraceId(TraceContext.currentTraceId());
        return result;
    }
}