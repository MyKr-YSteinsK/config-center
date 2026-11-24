package com.example.configcenter.migration;

import com.example.configcenter.config.ApiKeyProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
class IdentityMigrationConfiguration {
    @Bean
    V4__ExactNames exactNamesMigration(ApiKeyProperties authorization,
                                     @Value("${config-center.identity-mapping-file:}") String mappingFile) {
        return new V4__ExactNames(authorization, mappingFile);
    }
}
