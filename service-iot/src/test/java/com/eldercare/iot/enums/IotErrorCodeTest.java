package com.eldercare.iot.enums;

import com.eldercare.common.core.exception.IErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IotErrorCode 验证测试
 */
class IotErrorCodeTest {

    @Test
    void all_error_codes_in_range() {
        for (IotErrorCode code : IotErrorCode.values()) {
            assertTrue(code.getCode() >= 212001 && code.getCode() <= 212999,
                    "错误码 " + code.name() + " 不在 212000-212999 号段内");
        }
    }

    @Test
    void implements_ierrorcode() {
        for (IotErrorCode code : IotErrorCode.values()) {
            assertInstanceOf(IErrorCode.class, code);
            assertNotNull(code.getMsg());
            assertNotNull(code.getHttpStatus());
        }
    }

    @Test
    void specific_codes() {
        assertEquals(212001, IotErrorCode.DEVICE_NOT_FOUND.getCode());
        assertEquals(HttpStatus.NOT_FOUND, IotErrorCode.DEVICE_NOT_FOUND.getHttpStatus());

        assertEquals(212002, IotErrorCode.DEVICE_ALREADY_REGISTERED.getCode());
        assertEquals(HttpStatus.CONFLICT, IotErrorCode.DEVICE_ALREADY_REGISTERED.getHttpStatus());

        assertEquals(212006, IotErrorCode.DEVICE_BINDING_EXISTS.getCode());
        assertEquals(HttpStatus.CONFLICT, IotErrorCode.DEVICE_BINDING_EXISTS.getHttpStatus());

        assertEquals(212010, IotErrorCode.DEVICE_MESSAGE_UNDELIVERABLE.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, IotErrorCode.DEVICE_MESSAGE_UNDELIVERABLE.getHttpStatus());

        assertEquals(212016, IotErrorCode.DEVICE_VERSION_CONFLICT.getCode());
        assertEquals(HttpStatus.CONFLICT, IotErrorCode.DEVICE_VERSION_CONFLICT.getHttpStatus());
    }

    @Test
    void error_codes_are_unique() {
        long distinctCount = java.util.Arrays.stream(IotErrorCode.values())
                .map(IotErrorCode::getCode)
                .distinct()
                .count();
        assertEquals(IotErrorCode.values().length, distinctCount, "IoT 错误码不得重复");
    }

    @Test
    void enum_basic_methods() {
        assertEquals("ACTIVE", LifecycleStatus.ACTIVE.getCode());
        assertEquals("启用", LifecycleStatus.ACTIVE.getDescription());
        assertEquals(LifecycleStatus.RETIRED, LifecycleStatus.fromCode("RETIRED"));

        assertEquals("ONLINE", OnlineStatus.ONLINE.getCode());
        assertEquals(OnlineStatus.UNKNOWN, OnlineStatus.fromCode("UNKNOWN"));

        assertEquals("ELDER", BindingType.ELDER.getCode());
        assertEquals("LOCATION", BindingType.LOCATION.getCode());

        assertEquals("ACTIVE", BindingStatus.ACTIVE.getCode());
        assertEquals("INACTIVE", BindingStatus.INACTIVE.getCode());

        assertEquals("PENDING", OutboxStatus.PENDING.getCode());
        assertEquals("SENT", OutboxStatus.SENT.getCode());
        assertEquals("FAILED", OutboxStatus.FAILED.getCode());
    }
}
