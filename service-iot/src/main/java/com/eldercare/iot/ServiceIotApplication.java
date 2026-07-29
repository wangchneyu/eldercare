package com.eldercare.iot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * iot-service 启动入口。
 * <p>
 * 物联网接入服务：MQTT 设备接入、协议解析、消息分发。
 */
@SpringBootApplication(
        scanBasePackages = "com.eldercare.iot",
        excludeName = {
                "org.redisson.spring.starter.RedissonAutoConfigurationV2",
                "com.eldercare.common.redis.config.RedisAutoConfiguration"
        })
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.eldercare")
@EnableScheduling
@MapperScan("com.eldercare.iot.mapper")
public class ServiceIotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceIotApplication.class, args);
    }
}
