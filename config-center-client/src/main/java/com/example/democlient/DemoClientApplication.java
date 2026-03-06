package com.example.democlient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoClientApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DemoClientApplication.class);
        // 这个客户端就是个 CLI，不需要为了演示去多开一个 Web Server。
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
