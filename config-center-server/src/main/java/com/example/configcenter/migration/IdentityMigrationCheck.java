package com.example.configcenter.migration;

import com.example.configcenter.config.ApiKeyProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@ImportAutoConfiguration({DataSourceAutoConfiguration.class, ValidationAutoConfiguration.class})
@EnableConfigurationProperties(ApiKeyProperties.class)
public final class IdentityMigrationCheck {
    private IdentityMigrationCheck() {}

    public static void run(String[] args) {
        try (var app = new SpringApplicationBuilder(IdentityMigrationCheck.class).web(WebApplicationType.NONE).run(args)) {
            if (!app.getEnvironment().matchesProfiles("mysql")) {
                throw new IllegalStateException("V4 preflight requires the mysql profile and an intact V3 database");
            }
            try (var db = app.getBean(DataSource.class).getConnection()) {
                db.setReadOnly(true);
                var inspection = IdentityMigrationPlan.inspect(db, app.getBean(ApiKeyProperties.class));
                for (var group : inspection.groups()) {
                    System.out.println("IDENTITY_V4_GROUP " + IdentityMigrationPlan.JSON.writeValueAsString(group.diagnostic()));
                }
                var mapping = app.getEnvironment().getProperty("config-center.identity-mapping-file", "");
                inspection.resolve(IdentityMigrationPlan.readMappings(mapping));
                List<String> installed = new ArrayList<>();
                try (var statement = db.createStatement();
                     var result = statement.executeQuery("""
                             SELECT version, success FROM flyway_schema_history ORDER BY installed_rank
                             """)) {
                    while (result.next()) {
                        installed.add(result.getBoolean(2) ? result.getString(1) : "failed");
                    }
                }
                if (!installed.equals(List.of("1", "2", "3"))) {
                    throw new IllegalStateException("V4 preflight requires Flyway versions 1, 2, 3 with no failed migration");
                }
                System.out.println("IDENTITY_V4_READY (read only)");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Identity migration check failed", failure);
        }
    }
}
