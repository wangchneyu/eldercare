package com.eldercare.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ServiceAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceAiApplication.class, args);
        System.out.println("====== Service AI 启动成功 ======");
    }
}