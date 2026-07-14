package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.auth.UserRemoteDTO;
import com.eldercare.common.feign.fallback.AuthFallbackFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FeignCompilationTest {

    @Test
    public void testCompilationAndStructure() {
        // 验证 DTO 类是否能成功加载与实例化
        UserRemoteDTO userRemoteDTO = new UserRemoteDTO();
        userRemoteDTO.setId(1L);
        userRemoteDTO.setUsername("test");
        assertNotNull(userRemoteDTO.getUsername());

        // 验证 FallbackFactory 类是否能成功加载与实例化
        AuthFallbackFactory authFallbackFactory = new AuthFallbackFactory();
        assertNotNull(authFallbackFactory);
    }
}
