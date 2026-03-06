package com.example.configcenter.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置，把限流拦截器挂进 Spring MVC。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitProperties props;

    public WebConfig(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(props));
    }
}
