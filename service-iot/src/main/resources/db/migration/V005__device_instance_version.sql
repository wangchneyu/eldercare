ALTER TABLE iot_device_instance
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN iot_device_instance.version IS '设备实例生命周期更新的乐观锁版本号';
