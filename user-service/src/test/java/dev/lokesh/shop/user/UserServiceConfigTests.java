package dev.lokesh.shop.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins application.yml: the port compose publishes, and a datasource that targets only
 * this service's own schema. Resolved without a Spring context and with OS environment
 * and system properties removed, so a DB_HOST set on the developer machine cannot leak in.
 */
class UserServiceConfigTests {

    private StandardEnvironment env;

    @BeforeEach
    void loadApplicationYml() throws IOException {
        env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
    }

    @Test
    void defaultsTargetOwnSchemaOnComposePublishedPort() {
        assertEquals("8081", env.getProperty("server.port"));
        assertEquals("user-service", env.getProperty("spring.application.name"));
        assertEquals("jdbc:mysql://localhost:3307/users_db", env.getProperty("spring.datasource.url"));
        assertEquals("user_svc", env.getProperty("spring.datasource.username"));
        assertEquals("user_svc_dev_pw", env.getProperty("spring.datasource.password"));
        assertEquals("always", env.getProperty("management.endpoint.health.show-components"));
    }

    @Test
    void composeEnvironmentOverridesHostPortAndPasswordButNotSchema() {
        env.getPropertySources().addFirst(new MapPropertySource("compose", Map.of(
                "DB_HOST", "mysql", "DB_PORT", "3306", "DB_PASSWORD", "from-env")));

        assertEquals("jdbc:mysql://mysql:3306/users_db", env.getProperty("spring.datasource.url"));
        assertEquals("user_svc", env.getProperty("spring.datasource.username"));
        assertEquals("from-env", env.getProperty("spring.datasource.password"));
    }
}
