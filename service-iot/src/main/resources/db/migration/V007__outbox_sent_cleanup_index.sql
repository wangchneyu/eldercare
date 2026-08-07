-- C16-2 (frozen, 2026-08-07): low-priority SENT cleanup uses a batch-paginated
-- DELETE ordered by (sent_at, id). This partial index lets the cleanup subquery
-- walk only terminal SENT rows in deletion order without scanning the whole
-- outbox table; PENDING/FAILED rows are never part of this index.
CREATE INDEX IF NOT EXISTS idx_outbox_sent_cleanup
    ON iot_mq_outbox (sent_at, id)
    WHERE status = 'SENT';
