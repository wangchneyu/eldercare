-- C16-3 (frozen, 2026-08-07): one-shot P0 delivery-window escalation.
--
-- Crossing created_at + escalationWindow does not change status, next_retry_at,
-- lease or rawEnvelopeJson: the record stays PENDING and remains claimable by
-- OutboxRetryTask. This migration only adds a one-time audit timestamp (and the
-- fixed reason) plus a partial index that lets the escalation scan walk only
-- not-yet-escalated PENDING rows in creation order.
--
-- No status constraint is modified and no ABANDONED state is introduced.
ALTER TABLE iot_mq_outbox
    ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS escalation_reason VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_outbox_pending_escalation
    ON iot_mq_outbox (created_at, id)
    WHERE status = 'PENDING' AND escalated_at IS NULL;