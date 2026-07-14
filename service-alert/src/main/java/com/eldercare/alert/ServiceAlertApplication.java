package com.eldercare.alert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ServiceAlertApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceAlertApplication.class, args);
        System.out.println("====== Service Alert 启动成功 ======");
    }
}