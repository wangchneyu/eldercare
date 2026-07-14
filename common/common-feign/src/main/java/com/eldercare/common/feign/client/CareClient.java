package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.care.CarePlanRemoteDTO;
import com.eldercare.common.feign.fallback.CareFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "service-care", fallbackFactory = CareFallbackFactory.class)
public interface CareClient {

    @GetMapping("/care/plan/info")
    CarePlanRemoteDTO getCarePlan(@RequestParam("planId") Long planId);
}
