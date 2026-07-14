package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.auth.UserRemoteDTO;
import com.eldercare.common.feign.fallback.AuthFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "service-auth", fallbackFactory = AuthFallbackFactory.class)
public interface AuthClient {

    @GetMapping("/auth/user/info")
    UserRemoteDTO getUserInfo(@RequestParam("username") String username);

    @GetMapping("/auth/token/validate")
    Boolean validateToken(@RequestParam("token") String token);
}
