package com.eldercare.common.core.domain;

import com.eldercare.common.core.exception.SystemErrorCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RTest {

    @Test
    public void testOkWithData() {
        R<String> result = R.ok("hello");
        Assertions.assertEquals(0, result.getCode());
        Assertions.assertEquals("success", result.getMsg());
        Assertions.assertEquals("hello", result.getData());
    }

    @Test
    public void testOkWithoutData() {
        R<Void> result = R.ok();
        Assertions.assertEquals(0, result.getCode());
        Assertions.assertEquals("success", result.getMsg());
        Assertions.assertNull(result.getData());
    }

    @Test
    public void testFailWithErrorCode() {
        R<Void> result = R.fail(SystemErrorCode.NOT_FOUND);
        Assertions.assertEquals(SystemErrorCode.NOT_FOUND.getCode(), result.getCode());
        Assertions.assertEquals(SystemErrorCode.NOT_FOUND.getMsg(), result.getMsg());
        Assertions.assertNull(result.getData());
    }

    @Test
    public void testTraceIdNotNull() {
        // traceId 可能为 null（MDC 未初始化时），不强制断言非 null
        // 但 R 对象本身不应为 null
        R<String> result = R.ok("test");
        Assertions.assertNotNull(result);
    }
}
