-- ============================================
-- iot-service V002：P0 Outbox 原始信封与租约/并发控制
-- ============================================

-- 完整 C05 原始信封（首发/补发字节级一致）
ALTER TABLE iot_mq_outbox
    ADD COLUMN IF NOT EXISTS raw_envelope JSONB;

COMMENT ON COLUMN iot_mq_outbox.raw_envelope IS '完整 C05 原始信封 JSON（首发/补发字节级一致）';

-- 为 iot_mq_outbox 增加原子 claim 所需的租约字段
ALTER TABLE iot_mq_outbox
    ADD COLUMN IF NOT EXISTS lease_expire_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claimed_by      VARCHAR(64);

COMMENT ON COLUMN iot_mq_outbox.lease_expire_at IS '租约过期时间，过期后其他实例可回收';
COMMENT ON COLUMN iot_mq_outbox.claimed_by IS '领取该记录的实例标识（如 IP+进程号）';

-- 加速 PENDING + 租约扫描
CREATE INDEX IF NOT EXISTS idx_outbox_pending_lease
    ON iot_mq_outbox (status, lease_expire_at, created_at)
    WHERE status = 'PENDING';
