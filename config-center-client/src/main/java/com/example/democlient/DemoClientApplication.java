package com.example.democlient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoClientApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DemoClientApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); // 关键：不启动 Web Server，只跑 CLI
        app.run(args);
    }
}