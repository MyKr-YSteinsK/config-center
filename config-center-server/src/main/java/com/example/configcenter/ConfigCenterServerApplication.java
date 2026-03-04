package com.example.configcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 这是 Spring Boot 的启动入口。
 * @SpringBootApplication 做了三件事（现在先记名字就行）：
 * 1) 开启自动配置（Auto Configuration）
 * 2) 扫描当前包及子包的组件（Component Scan）
 * 3) 允许你写 @Controller/@Service/@Repository 等组件
 */
@SpringBootApplication
public class ConfigCenterServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigCenterServerApplication.class, args);
    }
}