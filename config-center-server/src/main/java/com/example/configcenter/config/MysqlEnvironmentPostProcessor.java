package com.example.configcenter.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 在创建数据源前报告缺失的 MySQL 环境变量，避免退化成模糊的 JDBC 错误。
 */
public class MysqlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final List<String> REQUIRED_VARIABLES = List.of(
            "CONFIG_CENTER_DB_URL",
            "CONFIG_CENTER_DB_USERNAME",
            "CONFIG_CENTER_DB_PASSWORD",
            "CONFIG_CENTER_API_KEY"
    );

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("mysql")) {
            return;
        }

        List<String> missing = REQUIRED_VARIABLES.stream()
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required MySQL environment variables: " + String.join(", ", missing));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
