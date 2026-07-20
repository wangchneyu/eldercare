package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.alert.AlertRemoteDTO;
import com.eldercare.common.feign.fallback.AlertFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "service-alert", fallbackFactory = AlertFallbackFactory.class)
public interface AlertClient {

    @GetMapping("/alert/info")
    AlertRemoteDTO getAlert(@RequestParam("alertId") Long alertId);
}
