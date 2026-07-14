package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.vital.VitalRecordRemoteDTO;
import com.eldercare.common.feign.fallback.VitalFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "service-vital", fallbackFactory = VitalFallbackFactory.class)
public interface VitalClient {

    @PostMapping("/vital/record/save")
    Boolean saveRecord(@RequestBody VitalRecordRemoteDTO record);
}
