package com.eldercare.iot; // ⚠️ 改包名

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ServiceIotApplication { // ⚠️ 改类名
    public static void main(String[] args) {
        SpringApplication.run(ServiceIotApplication.class, args);
        System.out.println("====== Service Device 启动成功 ======"); // ⚠️ 改打印语
    }
}