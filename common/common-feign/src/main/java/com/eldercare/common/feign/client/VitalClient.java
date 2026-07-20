package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.vital.VitalRecordRemoteDTO;
import com.eldercare.common.feign.fallback.VitalFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "service-vital", fallbackFactory = VitalFallbackFactory.class)
public interface VitalClient {

    @GetMapping("/vital/record/latest")
    VitalRecordRemoteDTO getLatestRecord(@RequestParam("elderId") Long elderId);
}
