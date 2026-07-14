package com.eldercare.security.domain;

/**
 * 用户角色枚举
 */
public enum UserRole {

    /** 系统管理员 — 拥有全部权限 */
    ADMIN,

    /** 家属用户 — 查看老人数据、接收告警 */
    FAMILY,

    /** 看护人员 — 管理老人日常照护 */
    CAREGIVER,

    /** 运营人员 — 平台运营管理 */
    OPERATOR
}
