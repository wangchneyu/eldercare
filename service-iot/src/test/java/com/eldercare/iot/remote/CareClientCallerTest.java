package com.eldercare.iot.remote;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.feign.client.CareClient;
import com.eldercare.common.feign.dto.care.CarePlanRemoteDTO;
import com.eldercare.iot.enums.IotErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void feignCallerAcceptsMatchingActiveCarePlan() {
        CareClient client = mock(CareClient.class);
        CarePlanRemoteDTO plan = new CarePlanRemoteDTO();
        plan.setElderId(1001L);
        plan.setStatus("ACTIVE");
        when(client.getCarePlan(1001L)).thenReturn(plan);

        assertDoesNotThrow(() -> new FeignCareClientCaller(client).validateActiveElder(1001L));
    }

    @Test
    void feignFailureMapsTo100007() {
        CareClient client = mock(CareClient.class);
        when(client.getCarePlan(1001L)).thenThrow(new IllegalStateException("service unavailable"));

        BizException exception = assertThrows(BizException.class,
                () -> new FeignCareClientCaller(client).validateActiveElder(1001L));

        assertEquals(SystemErrorCode.REMOTE_CALL_FAILED, exception.getErrorCode());
    }
}
