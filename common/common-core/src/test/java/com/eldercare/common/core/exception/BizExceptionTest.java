package com.eldercare.common.core.exception;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BizExceptionTest {

    @Test
    public void testConstructorWithErrorCode() {
        BizException ex = new BizException(SystemErrorCode.NOT_FOUND);
        Assertions.assertEquals(SystemErrorCode.NOT_FOUND.getCode(), ex.getErrorCode().getCode());
        Assertions.assertEquals(SystemErrorCode.NOT_FOUND.getMsg(), ex.getErrorCode().getMsg());
        Assertions.assertEquals(SystemErrorCode.NOT_FOUND.getMsg(), ex.getMessage());
    }

    @Test
    public void testConstructorWithCause() {
        RuntimeException cause = new RuntimeException("底层异常");
        BizException ex = new BizException(SystemErrorCode.INTERNAL_ERROR, cause);
        Assertions.assertEquals(SystemErrorCode.INTERNAL_ERROR.getCode(), ex.getErrorCode().getCode());
        Assertions.assertSame(cause, ex.getCause());
    }

    @Test
    public void testGetErrorCode() {
        BizException ex = new BizException(SystemErrorCode.UNAUTHORIZED);
        Assertions.assertSame(SystemErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }
}
