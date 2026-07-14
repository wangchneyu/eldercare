package com.eldercare.ai; // ⚠️ 改包名

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ServiceAiApplication { // ⚠️ 改类名
    public static void main(String[] args) {
        SpringApplication.run(ServiceAiApplication.class, args);
        System.out.println("====== Service Device 启动成功 ======"); // ⚠️ 改打印语
    }
}