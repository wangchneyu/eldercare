ALTER TABLE iot_mq_outbox
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_outbox_pending_retry
    ON iot_mq_outbox (status, next_retry_at, lease_expire_at, created_at)
    WHERE status = 'PENDING';
