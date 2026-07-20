package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.RemoteCallException;
import com.eldercare.common.feign.client.FamilyClient;
import com.eldercare.common.feign.dto.family.FamilyMemberRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class FamilyFallbackFactory implements FallbackFactory<FamilyClient> {

    @Override
    public FamilyClient create(Throwable cause) {
        log.error("Feign call to service-family failed. Reason: {}", cause.getMessage(), cause);
        return new FamilyClient() {
            @Override
            public List<FamilyMemberRemoteDTO> getFamilyMembers(Long elderId) {
                throw new RemoteCallException(cause);
            }
        };
    }
}
