package com.eldercare.iot.enums;

import com.eldercare.common.core.exception.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * IoT 服务错误码（号段 212000-212999）
 */
@Getter
@AllArgsConstructor
public enum IotErrorCode implements IErrorCode {

    DEVICE_NOT_FOUND(212001, "设备不存在", HttpStatus.NOT_FOUND),
    DEVICE_ALREADY_REGISTERED(212002, "设备已注册", HttpStatus.CONFLICT),
    DEVICE_STATUS_NOT_ALLOWED(212003, "设备状态不允许此操作", HttpStatus.CONFLICT),
    DEVICE_PROTOCOL_UNSUPPORTED(212004, "设备协议不受支持", HttpStatus.BAD_REQUEST),
    DEVICE_MESSAGE_INVALID(212005, "设备消息格式不合法", HttpStatus.BAD_REQUEST),
    DEVICE_BINDING_EXISTS(212006, "设备已存在有效绑定", HttpStatus.CONFLICT),
    DEVICE_NOT_BOUND(212007, "设备当前未绑定", HttpStatus.NOT_FOUND),
    DEVICE_BINDING_INVALID(212008, "设备绑定信息不合法", HttpStatus.BAD_REQUEST),
    DEVICE_MESSAGE_CONFLICT(212009, "设备消息重复或冲突", HttpStatus.CONFLICT),
    DEVICE_MESSAGE_UNDELIVERABLE(212010, "设备消息暂时无法投递", HttpStatus.SERVICE_UNAVAILABLE),
    DEVICE_MODEL_NOT_FOUND(212011, "设备型号不存在", HttpStatus.NOT_FOUND),
    DEVICE_MODEL_CODE_DUPLICATE(212012, "设备型号编码已存在", HttpStatus.CONFLICT),
    DEVICE_MODEL_IN_USE(212013, "设备型号下存在设备实例，无法删除", HttpStatus.CONFLICT);

    private final int code;
    private final String msg;
    private final HttpStatus httpStatus;
}
