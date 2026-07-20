package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.RemoteCallException;
import com.eldercare.common.feign.client.VitalClient;
import com.eldercare.common.feign.dto.vital.VitalRecordRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VitalFallbackFactory implements FallbackFactory<VitalClient> {

    @Override
    public VitalClient create(Throwable cause) {
        log.error("Feign call to service-vital failed. Reason: {}", cause.getMessage(), cause);
        return new VitalClient() {
            @Override
            public VitalRecordRemoteDTO getLatestRecord(Long elderId) {
                throw new RemoteCallException(cause);
            }
        };
    }
}
