package com.eldercare.common.feign.fallback;

import com.eldercare.common.core.exception.RemoteCallException;
import com.eldercare.common.feign.client.OperationClient;
import com.eldercare.common.feign.dto.operation.OperationRemoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OperationClient 降级工厂
 */
@Slf4j
@Component
public class OperationFallbackFactory implements FallbackFactory<OperationClient> {

    @Override
    public OperationClient create(Throwable cause) {
        log.error("Feign call to service-operation failed. Reason: {}", cause.getMessage(), cause);
        return new OperationClient() {
            @Override
            public OperationRemoteDTO getEmployee(Long employeeId) {
                throw new RemoteCallException("service-operation",
                        "/operation/employee/" + employeeId, cause);
            }

            @Override
            public List<OperationRemoteDTO> listEmployeesByRole(String role) {
                throw new RemoteCallException("service-operation",
                        "/operation/employee/list?role=" + role, cause);
            }
        };
    }
}
