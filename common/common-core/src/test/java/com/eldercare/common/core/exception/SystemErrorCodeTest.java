package com.eldercare.common.core.exception;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SystemErrorCodeTest {

    @Test
    public void testAllErrorCodesNotNull() {
        for (SystemErrorCode code : SystemErrorCode.values()) {
            Assertions.assertNotNull(code.getCode(), code.name() + " code 不应为空");
            Assertions.assertNotNull(code.getMsg(), code.name() + " msg 不应为空");
            Assertions.assertNotNull(code.getHttpStatus(), code.name() + " httpStatus 不应为空");
        }
    }

    @Test
    public void testErrorCodeCount() {
        // 与《错误码及异常设计》2.2 节一致，共 10 个枚举值
        Assertions.assertEquals(10, SystemErrorCode.values().length);
    }

    @Test
    public void testSpecificErrorCodes() {
        Assertions.assertEquals(100001, SystemErrorCode.INTERNAL_ERROR.getCode());
        Assertions.assertEquals(100009, SystemErrorCode.NOT_FOUND.getCode());
        Assertions.assertEquals(110002, SystemErrorCode.FORBIDDEN.getCode());
    }
}
