-- ============================================
-- iot-service 数据库表设计
-- 基于：《iot-设备表草稿.sql》V0.2
-- 补充：P0 去重三列（outbox）、乐观锁 version 列（model）
-- ============================================

-- 1. 设备型号表
CREATE TABLE iot_device_model (
    id                          BIGINT          NOT NULL,
    model_code                  VARCHAR(64)     NOT NULL,
    manufacturer                VARCHAR(128)    NOT NULL,
    device_type                 VARCHAR(32)     NOT NULL,
    parser_code                 VARCHAR(64)     NOT NULL,
    heartbeat_timeout_seconds   INT             NOT NULL DEFAULT 15,
    description                 VARCHAR(256),
    enabled                     BOOLEAN         NOT NULL DEFAULT TRUE,
    version                     INT             NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ,

    CONSTRAINT pk_iot_device_model PRIMARY KEY (id),
    CONSTRAINT uk_model_code UNIQUE (model_code)
);
COMMENT ON TABLE iot_device_model IS '设备型号';
COMMENT ON COLUMN iot_device_model.device_type IS 'MATTRESS / RADAR / SOS_BUTTON / SOS_PULL / SOS_PENDANT / FALL_SENSOR / MEDICINE_PAD / WATCH';
COMMENT ON COLUMN iot_device_model.parser_code IS '对应解析器标识，如 simulator / mattress-v1 / radar-v1';
COMMENT ON COLUMN iot_device_model.heartbeat_timeout_seconds IS '心跳超时秒数，范围 5-300';
COMMENT ON COLUMN iot_device_model.enabled IS 'FALSE 后新设备不能选该型号，已有设备不受影响';
COMMENT ON COLUMN iot_device_model.version IS '乐观锁版本号';


-- 2. 设备实例表（含在线状态快照）
CREATE TABLE iot_device_instance (
    id                  BIGINT          NOT NULL,
    device_id           VARCHAR(64)     NOT NULL,
    serial_no           VARCHAR(128)    NOT NULL,
    device_name         VARCHAR(128),
    model_id            BIGINT          NOT NULL,
    mqtt_client_id      VARCHAR(128)    NOT NULL,
    lifecycle_status    VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    online_status       VARCHAR(16)     DEFAULT 'UNKNOWN',
    version             INT             NOT NULL DEFAULT 0,
    last_heartbeat_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,

    CONSTRAINT pk_iot_device_instance PRIMARY KEY (id),
    CONSTRAINT uk_device_id UNIQUE (device_id),
    CONSTRAINT uk_serial_no UNIQUE (serial_no),
    CONSTRAINT uk_mqtt_client_id UNIQUE (mqtt_client_id),
    CONSTRAINT uk_model_serial UNIQUE (model_id, serial_no)
);
COMMENT ON TABLE iot_device_instance IS '设备实例';
COMMENT ON COLUMN iot_device_instance.device_id IS 'IoT 权威业务 ID，全平台统一 varchar(64)';
COMMENT ON COLUMN iot_device_instance.lifecycle_status IS 'ACTIVE（启用）/ DISABLED（停用）/ RETIRED（退役）';
COMMENT ON COLUMN iot_device_instance.online_status IS 'ONLINE / OFFLINE / UNKNOWN——内存维护，数据库为快照';
COMMENT ON COLUMN iot_device_instance.last_heartbeat_at IS '最近一次收到心跳的时间';


-- 3. 设备绑定表
CREATE TABLE iot_device_binding (
    id                  BIGINT          NOT NULL,
    binding_id          VARCHAR(64)     NOT NULL,
    device_id           VARCHAR(64)     NOT NULL,
    binding_type        VARCHAR(16)     NOT NULL,
    elder_id            BIGINT,
    park_id             VARCHAR(64),
    building_id         VARCHAR(64),
    room_id             VARCHAR(64),
    room_no             VARCHAR(64),
    location_id         VARCHAR(64),
    location_type       VARCHAR(24),
    location_name       VARCHAR(128),
    floor_id            VARCHAR(64),
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    active_from         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    inactive_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_iot_device_binding PRIMARY KEY (id),
    CONSTRAINT uk_binding_id UNIQUE (binding_id)
);
CREATE UNIQUE INDEX uk_device_active_binding
    ON iot_device_binding (device_id, binding_type) WHERE status = 'ACTIVE';
CREATE INDEX idx_binding_device ON iot_device_binding (device_id);
CREATE INDEX idx_binding_elder ON iot_device_binding (elder_id) WHERE elder_id IS NOT NULL;

COMMENT ON TABLE iot_device_binding IS '设备绑定记录（含历史），同一设备可同时有 ELDER 和 LOCATION 各一条 ACTIVE 绑定';
COMMENT ON COLUMN iot_device_binding.binding_id IS '绑定快照 ID——ELDER 绑定 → SOS payload 的 bindingId；LOCATION 绑定 → locationBindingId';
COMMENT ON COLUMN iot_device_binding.binding_type IS 'ELDER / LOCATION';
COMMENT ON COLUMN iot_device_binding.location_type IS 'ROOM / CORRIDOR / PUBLIC_AREA / BUILDING_ENTRANCE / OUTDOOR_POINT / OTHER';


-- 4. P0 事件发件箱（含 P0 去重三列）
CREATE TABLE iot_mq_outbox (
    id                      BIGINT          NOT NULL,
    event_id                VARCHAR(64)     NOT NULL,
    device_id               VARCHAR(64),
    source_message_id       VARCHAR(64),
    event_type              VARCHAR(32),
    topic                   VARCHAR(64)     NOT NULL,
    tag                     VARCHAR(32),
    payload                 JSONB           NOT NULL,
    status                  VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    retry_count             INT             NOT NULL DEFAULT 0,
    last_error              TEXT,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    sent_at                 TIMESTAMPTZ,
    next_retry_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_iot_mq_outbox PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    CONSTRAINT uk_outbox_dedup UNIQUE (device_id, source_message_id, event_type)
);
CREATE INDEX idx_outbox_status ON iot_mq_outbox (status, next_retry_at, created_at) WHERE status = 'PENDING';

COMMENT ON TABLE iot_mq_outbox IS 'P0 事件发件箱——SOS/跌倒事件先落库再同步发送，服务重启后补发未完成记录';
COMMENT ON COLUMN iot_mq_outbox.status IS 'PENDING / SENT / FAILED';
COMMENT ON COLUMN iot_mq_outbox.device_id IS 'P0 去重字段：设备 ID';
COMMENT ON COLUMN iot_mq_outbox.source_message_id IS 'P0 去重字段：上游消息 ID';
COMMENT ON COLUMN iot_mq_outbox.event_type IS 'P0 去重字段：事件类型（SOS_TRIGGERED / FALL_DETECTED）';
