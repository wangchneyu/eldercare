package com.eldercare.vital;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient // 开启 Nacos 服务发现
@SpringBootApplication(scanBasePackages = "com.eldercare") // 扫描 com.heyuan 下的所有包，确保 common 组件被加载
public class ServiceVitalApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceVitalApplication.class, args);
    }
}