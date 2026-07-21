package com.eldercare.common.feign.client;

import com.eldercare.common.feign.dto.operation.OperationRemoteDTO;
import com.eldercare.common.feign.fallback.OperationFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 运营管理服务 Feign 客户端
 * <p>
 * 对外暴露运营管理（合同、排班、库存等）相关的远程调用接口。
 */
@FeignClient(value = "service-operation", fallbackFactory = OperationFallbackFactory.class)
public interface OperationClient {

    /**
     * 查询员工信息
     */
    @GetMapping("/operation/employee/{employeeId}")
    OperationRemoteDTO getEmployee(@PathVariable("employeeId") Long employeeId);

    /**
     * 按角色查询员工列表
     */
    @GetMapping("/operation/employee/list")
    List<OperationRemoteDTO> listEmployeesByRole(@RequestParam("role") String role);
}
