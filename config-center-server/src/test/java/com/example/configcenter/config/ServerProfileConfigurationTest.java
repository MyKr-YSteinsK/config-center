package com.example.configcenter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ServerProfileConfigurationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void testProfile_startsWithIsolatedH2WithoutFlyway() throws Exception {
        assertTrue(environment.matchesProfiles("test"));
        assertEquals("127.0.0.1", environment.getProperty("server.address"));
        assertTrue(environment.getProperty("spring.datasource.url", "")
                .startsWith("jdbc:h2:mem:configdb-test-"));
        assertEquals("create-drop", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", environment.getProperty("spring.flyway.enabled"));
        assertEquals("false", environment.getProperty("spring.h2.console.enabled"));

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:configdb-test-"));
        }
    }

    @Test
    void profileFiles_keepCommonLocalAndMysqlResponsibilitiesSeparate() throws Exception {
        PropertySource<?> common = load("application.yml");
        PropertySource<?> local = load("application-local.yml");
        PropertySource<?> mysql = load("application-mysql.yml");

        assertEquals("local", common.getProperty("spring.profiles.default"));
        assertEquals("${SERVER_ADDRESS:127.0.0.1}", common.getProperty("server.address"));
        assertNull(common.getProperty("spring.datasource.url"));
        assertTrue(local.getProperty("spring.datasource.url").toString().startsWith("jdbc:h2:mem:"));
        assertEquals("update", local.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals(false, local.getProperty("spring.flyway.enabled"));
        assertEquals("${CONFIG_CENTER_DB_URL}", mysql.getProperty("spring.datasource.url"));
        assertEquals("${CONFIG_CENTER_DB_USERNAME}", mysql.getProperty("spring.datasource.username"));
        assertEquals("${CONFIG_CENTER_DB_PASSWORD}", mysql.getProperty("spring.datasource.password"));
        assertEquals("validate", mysql.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals(true, mysql.getProperty("spring.flyway.enabled"));
        assertEquals(false, mysql.getProperty("spring.h2.console.enabled"));
        assertEquals("${CONFIG_CENTER_API_KEY}", mysql.getProperty("security.api-keys[0].key"));
    }

    @Test
    void serverAddress_canBeOverriddenWithoutChangingComposeHostBinding() throws Exception {
        MockEnvironment overridden = new MockEnvironment();
        overridden.getPropertySources().addFirst(new MapPropertySource(
                "override", Map.of("SERVER_ADDRESS", "127.0.0.2")));
        overridden.getPropertySources().addLast(load("application.yml"));

        assertEquals("127.0.0.2", overridden.getProperty("server.address"));
    }

    @Test
    void mysqlProfile_missingDatabaseEnvironmentFailsWithVariableName() throws Exception {
        PropertySource<?> mysql = load("application-mysql.yml");
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(mysql);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.getProperty("spring.datasource.url"));

        assertTrue(error.getMessage().contains("CONFIG_CENTER_DB_URL"));
        assertFalse(error.getMessage().contains("password"));
    }

    @Test
    void mysqlProfile_startupValidationListsMissingVariablesWithoutValues() {
        MockEnvironment mysqlEnvironment = new MockEnvironment();
        mysqlEnvironment.setActiveProfiles("mysql");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MysqlEnvironmentPostProcessor().postProcessEnvironment(
                        mysqlEnvironment, new SpringApplication()));

        assertTrue(error.getMessage().contains("CONFIG_CENTER_DB_URL"));
        assertTrue(error.getMessage().contains("CONFIG_CENTER_DB_USERNAME"));
        assertTrue(error.getMessage().contains("CONFIG_CENTER_DB_PASSWORD"));
        assertTrue(error.getMessage().contains("CONFIG_CENTER_API_KEY"));
    }

    @Test
    void mysqlProfile_rejectsMissingOrBlankApiKey() {
        MockEnvironment mysqlEnvironment = mysqlEnvironmentWithDatabaseVariables();

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> new MysqlEnvironmentPostProcessor().postProcessEnvironment(
                        mysqlEnvironment, new SpringApplication()));
        assertTrue(missing.getMessage().contains("CONFIG_CENTER_API_KEY"));

        mysqlEnvironment.setProperty("CONFIG_CENTER_API_KEY", " ");
        IllegalStateException blank = assertThrows(IllegalStateException.class,
                () -> new MysqlEnvironmentPostProcessor().postProcessEnvironment(
                        mysqlEnvironment, new SpringApplication()));
        assertTrue(blank.getMessage().contains("CONFIG_CENTER_API_KEY"));

        mysqlEnvironment.setProperty("CONFIG_CENTER_API_KEY", "mysql-test-key");
        new MysqlEnvironmentPostProcessor().postProcessEnvironment(
                mysqlEnvironment, new SpringApplication());
    }

    private MockEnvironment mysqlEnvironmentWithDatabaseVariables() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mysql");
        environment.setProperty("CONFIG_CENTER_DB_URL", "jdbc:mysql://example/config_center");
        environment.setProperty("CONFIG_CENTER_DB_USERNAME", "config_center_app");
        environment.setProperty("CONFIG_CENTER_DB_PASSWORD", "test-password");
        return environment;
    }

    private PropertySource<?> load(String resource) throws Exception {
        return new YamlPropertySourceLoader()
                .load(resource, new ClassPathResource(resource))
                .get(0);
    }
}
