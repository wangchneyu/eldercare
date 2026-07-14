package com.eldercare.iot;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ServiceIotApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceIotApplication.class, args);
        System.out.println("====== Service IoT 启动成功 ======");
    }
}