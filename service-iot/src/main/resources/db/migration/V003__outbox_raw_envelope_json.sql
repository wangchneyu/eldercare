-- JSONB is retained for querying, while this column preserves the send bytes.
ALTER TABLE iot_mq_outbox
    ADD COLUMN IF NOT EXISTS raw_envelope_json TEXT;

COMMENT ON COLUMN iot_mq_outbox.raw_envelope_json IS
    '首次写入的 C05 原始 JSON 文本，首发与补发必须原样发送';

UPDATE iot_mq_outbox
SET raw_envelope_json = raw_envelope::text
WHERE raw_envelope_json IS NULL
  AND raw_envelope IS NOT NULL;
