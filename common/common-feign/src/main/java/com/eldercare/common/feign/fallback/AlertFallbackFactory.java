package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.RemoteCallException;
import com.eldercare.common.feign.client.AlertClient;
import com.eldercare.common.feign.dto.alert.AlertRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AlertFallbackFactory implements FallbackFactory<AlertClient> {

    @Override
    public AlertClient create(Throwable cause) {
        log.error("Feign call to service-alert failed. Reason: {}", cause.getMessage(), cause);
        return new AlertClient() {
            @Override
            public AlertRemoteDTO getAlert(Long alertId) {
                throw new RemoteCallException(cause);
            }
        };
    }
}
