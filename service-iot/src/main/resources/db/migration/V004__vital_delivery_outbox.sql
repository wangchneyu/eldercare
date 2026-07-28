-- Failure-only C04 delivery persistence. Normal vital-sign traffic does not use this table.
CREATE TABLE IF NOT EXISTS iot_vital_delivery_outbox (
    id                  BIGINT          NOT NULL,
    event_id            VARCHAR(64)     NOT NULL,
    device_id           VARCHAR(64)     NOT NULL,
    source_message_id   VARCHAR(128)    NOT NULL,
    device_type         VARCHAR(32)     NOT NULL,
    topic               VARCHAR(64)     NOT NULL,
    tag                 VARCHAR(32)     NOT NULL,
    trace_id            VARCHAR(128),
    raw_envelope_json   TEXT            NOT NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    retry_count         INT             NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    next_retry_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ     NOT NULL,
    sent_at             TIMESTAMPTZ,
    quarantined_at      TIMESTAMPTZ,
    lease_expire_at     TIMESTAMPTZ,
    claimed_by          VARCHAR(64),

    CONSTRAINT pk_iot_vital_delivery_outbox PRIMARY KEY (id),
    CONSTRAINT uk_vital_delivery_event_id UNIQUE (event_id),
    CONSTRAINT ck_vital_delivery_status CHECK (status IN ('PENDING', 'SENT', 'QUARANTINED'))
);

CREATE INDEX IF NOT EXISTS idx_vital_delivery_pending_lease
    ON iot_vital_delivery_outbox (status, next_retry_at, lease_expire_at, created_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE iot_vital_delivery_outbox IS
    'C04 failure-only compensation; normal vital traffic is not persisted and the original envelope is reused.';
