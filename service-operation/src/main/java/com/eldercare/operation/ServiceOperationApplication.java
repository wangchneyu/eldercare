package com.eldercare.operation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient // 开启服务注册发现
public class ServiceOperationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceOperationApplication.class, args);
        System.out.println("====== Service System (用户服务) 启动成功 ======");
    }
}