package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.iot.DeviceTelemetryRemoteDTO;
import com.eldercare.common.feign.fallback.IotFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "service-iot", fallbackFactory = IotFallbackFactory.class)
public interface IotClient {

    @GetMapping("/iot/telemetry/latest")
    DeviceTelemetryRemoteDTO getLatestTelemetry(@RequestParam("deviceId") String deviceId);
}
