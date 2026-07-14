package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.alert.AlertRemoteDTO;
import com.eldercare.common.feign.fallback.AlertFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "service-alert", fallbackFactory = AlertFallbackFactory.class)
public interface AlertClient {

    @PostMapping("/alert/create")
    AlertRemoteDTO createAlert(@RequestBody AlertRemoteDTO alert);
}
