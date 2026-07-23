package com.eldercare.common.core.exception;

/**
 * 同步依赖调用失败。
 *
 * <p>该异常只表达 Feign、AI 或第三方 SDK 等基础设施依赖不可用，
 * 不用于表达领域规则拒绝。</p>
 */
public class RemoteCallException extends RuntimeException {

    public RemoteCallException(Throwable cause) {
        super(SystemErrorCode.REMOTE_CALL_FAILED.getMsg(), cause);
    }

    public RemoteCallException(String serviceName, String path, Throwable cause) {
        super(SystemErrorCode.REMOTE_CALL_FAILED.getMsg() + " [service=" + serviceName + ", path=" + path + "]", cause);
    }
}
