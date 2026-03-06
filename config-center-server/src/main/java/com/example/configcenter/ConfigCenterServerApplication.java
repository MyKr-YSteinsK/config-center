package com.example.configcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 服务端启动入口。
 * 这个类本身没业务，但它像总电闸一样，项目能不能跑先看它。
 */
@SpringBootApplication
public class ConfigCenterServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigCenterServerApplication.class, args);
    }
}
