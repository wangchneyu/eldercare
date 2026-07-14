package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.feign.client.CareClient;
import com.eldercare.common.feign.dto.care.CarePlanRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CareFallbackFactory implements FallbackFactory<CareClient> {

    @Override
    public CareClient create(Throwable cause) {
        log.error("Feign call to service-care failed. Reason: {}", cause.getMessage(), cause);
        return new CareClient() {
            @Override
            public CarePlanRemoteDTO getCarePlan(Long planId) {
                throw new BizException(SystemErrorCode.REMOTE_CALL_FAILED, cause);
            }
        };
    }
}
