package com.eldercare.iot.remote;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.iot.enums.IotErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CareClientCallerTest {

    @Test
    void mockActiveElderPassesValidation() {
        CareClientCaller caller = new MockCareClientCaller("active");

        assertDoesNotThrow(() -> caller.validateActiveElder(1001L));
    }

    @Test
    void mockUnavailableMapsTo100007() {
        CareClientCaller caller = new MockCareClientCaller("unavailable");

        BizException exception = assertThrows(BizException.class,
                () -> caller.validateActiveElder(1001L));

        assertEquals(SystemErrorCode.REMOTE_CALL_FAILED, exception.getErrorCode());
    }

    @Test
    void mockMissingElderIsRejected() {
        CareClientCaller caller = new MockCareClientCaller("not-found");

        BizException exception = assertThrows(BizException.class,
                () -> caller.validateActiveElder(1001L));

        assertEquals(IotErrorCode.ELDER_NOT_FOUND, exception.getErrorCode());
    }

}
