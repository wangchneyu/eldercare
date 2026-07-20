package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.RemoteCallException;
import com.eldercare.common.feign.client.AuthClient;
import com.eldercare.common.feign.dto.auth.UserRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuthFallbackFactory implements FallbackFactory<AuthClient> {

    @Override
    public AuthClient create(Throwable cause) {
        log.error("Feign call to service-auth failed. Reason: {}", cause.getMessage(), cause);
        return new AuthClient() {
            @Override
            public UserRemoteDTO getUserInfo(String username) {
                throw new RemoteCallException(cause);
            }

            @Override
            public Boolean validateToken(String token) {
                throw new RemoteCallException(cause);
            }
        };
    }
}
