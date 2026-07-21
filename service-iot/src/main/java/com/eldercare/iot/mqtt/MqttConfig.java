package com.eldercare.iot.mqtt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "mqtt")
public class MqttConfig {

    private String brokerUrl = "tcp://localhost:1883";
    private String clientId = "iot-service";
    private String username;
    private String password;
    private String[] topics = {"elder/+/+/+/up/+"};
    private int[] qos = {1};
}
