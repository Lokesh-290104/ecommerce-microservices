package dev.lokesh.shop.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL 8.4, set up by the repo's own docker/mysql/init/01-databases.sh with the same
 * image and settings as docker-compose.yml. Covers what the offline tests cannot: the service
 * connects as its own user, the db health contributor is UP, and MySQL itself enforces
 * database-per-service. Skipped (not failed) when no Docker daemon is reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MySqlSchemaIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> SERVICES = List.of("user", "product", "order", "payment");

    @Container
    static final MySQLContainer MYSQL = withServicePasswords(new MySQLContainer("mysql:8.4"))
            .withEnv("MYSQL_ROOT_HOST", "localhost") // as in docker-compose.yml
            .withCopyFileToContainer(
                    MountableFile.forHostPath(ROOT.resolve("docker/mysql/init/01-databases.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-databases.sh");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url("users_db"));
        registry.add("spring.datasource.username", () -> "user_svc");
        registry.add("spring.datasource.password", () -> password("user"));
    }

    @Autowired
    MockMvc mvc;

    @Test
    void dbHealthIsUpAgainstRealMysql() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @ParameterizedTest(name = "{0}_svc")
    @CsvSource({"user", "product", "order", "payment"})
    void eachServiceUserOwnsOnlyItsSchema(String svc) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(svc + "s_db"), svc + "_svc", password(svc))) {
            c.createStatement().execute("CREATE TABLE probe (id INT)");
            c.createStatement().execute("DROP TABLE probe");

            for (String other : SERVICES) {
                if (!other.equals(svc)) {
                    assertThrows(SQLException.class,
                            () -> c.createStatement().execute("CREATE TABLE " + other + "s_db.probe (id INT)"),
                            svc + "_svc must not write to " + other + "s_db");
                }
            }
        }
    }

    @Test
    void rootCannotLogInOverTheNetwork() {
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection(url("mysql"), "root", MYSQL.getPassword()).close());
        assertEquals(1045, e.getErrorCode(), e.getMessage()); // ER_ACCESS_DENIED_ERROR
    }

    private static MySQLContainer withServicePasswords(MySQLContainer container) {
        SERVICES.forEach(svc -> container.withEnv(svc.toUpperCase() + "_SVC_DB_PASSWORD", password(svc)));
        return container;
    }

    private static String password(String svc) {
        return svc + "_svc_it_pw";
    }

    private static String url(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database;
    }
}
