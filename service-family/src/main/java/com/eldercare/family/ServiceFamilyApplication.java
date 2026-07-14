package com.eldercare.family;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient // 开启 Nacos 服务发现
@SpringBootApplication(scanBasePackages = "com.eldercare") // 扫描 com.eldercare 下的所有包，确保 common 组件被加载
public class ServiceFamilyApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceFamilyApplication.class, args);
    }
}