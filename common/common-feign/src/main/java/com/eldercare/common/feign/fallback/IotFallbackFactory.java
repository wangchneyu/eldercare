package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.BizException;
import com.eldercare.common.core.exception.SystemErrorCode;
import com.eldercare.common.feign.client.IotClient;
import com.eldercare.common.feign.dto.iot.DeviceTelemetryRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IotFallbackFactory implements FallbackFactory<IotClient> {

    @Override
    public IotClient create(Throwable cause) {
        log.error("Feign call to service-iot failed. Reason: {}", cause.getMessage(), cause);
        return new IotClient() {
            @Override
            public Boolean reportTelemetry(DeviceTelemetryRemoteDTO telemetry) {
                throw new BizException(SystemErrorCode.REMOTE_CALL_FAILED, cause);
            }
        };
    }
}
