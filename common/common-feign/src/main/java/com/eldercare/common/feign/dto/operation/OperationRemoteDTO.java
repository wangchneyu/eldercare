package com.eldercare.common.feign.dto.operation;

import lombok.Data;

import java.io.Serializable;

/**
 * 运营管理远程 DTO（员工信息）
 */
@Data
public class OperationRemoteDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String employeeNo;
    private String role;
    private String department;
    private String phone;
}
