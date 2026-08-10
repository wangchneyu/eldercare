package com.eldercare.iot.mq;

/**
 * RocketMQ Topic / Tag 常量（按已冻结契约）。
 */
public final class MqTopicConstants {

    private MqTopicConstants() {
    }

    public static final String VITAL_SIGN_TOPIC = "elder-vital-raw";
    public static final String SOS_EVENT_TOPIC = "elder-sos-event";
    public static final String DEVICE_STATUS_TOPIC = "elder-device-status";

    public static final String TAG_SOS = "SOS";
    public static final String TAG_FALL = "FALL";

    public static final String PRODUCER = "service-iot@1.0.0";
}
