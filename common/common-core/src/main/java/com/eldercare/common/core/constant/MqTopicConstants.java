package com.eldercare.common.core.constant;

/**
 * MQ Topic 常量定义
 * <p>
 * 命名规范：{domain}_{event_type}_{version}
 * 如：iot_device_data_v1
 */
public final class MqTopicConstants {

    private MqTopicConstants() {
        // 工具类禁止实例化
    }

    // ==================== 物联网 ====================

    /** 设备数据上报 */
    public static final String IOT_DEVICE_DATA = "iot_device_data_v1";
    /** 设备上下线事件 */
    public static final String IOT_DEVICE_STATUS = "iot_device_status_v1";
    /** 设备告警 */
    public static final String IOT_DEVICE_ALARM = "iot_device_alarm_v1";

    // ==================== 体征监测 ====================

    /** 体征数据采集 */
    public static final String VITAL_DATA_COLLECT = "vital_data_collect_v1";
    /** 体征异常告警 */
    public static final String VITAL_ABNORMAL_ALERT = "vital_abnormal_alert_v1";

    // ==================== 告警通知 ====================

    /** 紧急告警（SOS） */
    public static final String ALERT_SOS = "alert_sos_v1";
    /** 跌倒检测告警 */
    public static final String ALERT_FALL = "alert_fall_v1";
    /** 围栏越界告警 */
    public static final String ALERT_GEOFENCE = "alert_geofence_v1";
    /** 低电量告警 */
    public static final String ALERT_LOW_BATTERY = "alert_low_battery_v1";

    // ==================== 运营审计 ====================

    /** 用户操作审计日志 */
    public static final String AUDIT_USER_OPERATION = "audit_user_operation_v1";
    /** 系统操作日志 */
    public static final String AUDIT_SYSTEM_LOG = "audit_system_log_v1";

    // ==================== 用户事件 ====================

    /** 用户注册事件 */
    public static final String USER_REGISTERED = "user_registered_v1";
    /** 用户信息变更 */
    public static final String USER_UPDATED = "user_updated_v1";

    // ==================== 家属关怀 ====================

    /** 关怀提醒通知 */
    public static final String FAMILY_CARE_REMINDER = "family_care_reminder_v1";
}
