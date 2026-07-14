package com.eldercare.common.core.exception;

public class BizException extends RuntimeException {
    private final IErrorCode errorCode;

    // 默认构造器：不填充堆栈，极大提升性能
    public BizException(IErrorCode errorCode) {
        super(errorCode.getMsg(), null, false, false);
        this.errorCode = errorCode;
    }

    // 保留 cause 的构造器：用于转换底层异常
    public BizException(IErrorCode errorCode, Throwable cause) {
        super(errorCode.getMsg(), cause, true, true);
        this.errorCode = errorCode;
    }

    public IErrorCode getErrorCode() {
        return errorCode;
    }
}