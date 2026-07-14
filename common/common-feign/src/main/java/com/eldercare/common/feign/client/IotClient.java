package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.iot.DeviceTelemetryRemoteDTO;
import com.eldercare.common.feign.fallback.IotFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "service-iot", fallbackFactory = IotFallbackFactory.class)
public interface IotClient {

    @PostMapping("/iot/telemetry/report")
    Boolean reportTelemetry(@RequestBody DeviceTelemetryRemoteDTO telemetry);
}
