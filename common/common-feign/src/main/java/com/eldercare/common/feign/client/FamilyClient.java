package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.family.FamilyMemberRemoteDTO;
import com.eldercare.common.feign.fallback.FamilyFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(value = "service-family", fallbackFactory = FamilyFallbackFactory.class)
public interface FamilyClient {

    @GetMapping("/family/members")
    List<FamilyMemberRemoteDTO> getFamilyMembers(@RequestParam("elderId") Long elderId);
}
