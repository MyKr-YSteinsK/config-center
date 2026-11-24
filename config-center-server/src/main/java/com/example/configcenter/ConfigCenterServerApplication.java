package com.example.configcenter;

import com.example.configcenter.migration.IdentityMigrationCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * 服务端启动入口。
 * 这个类本身没业务，但它像总电闸一样，项目能不能跑先看它。
 */
@SpringBootApplication
public class ConfigCenterServerApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--check-identity-migration")) {
            IdentityMigrationCheck.run(args);
            return;
        }
        SpringApplication.run(ConfigCenterServerApplication.class, args);
    }
}
