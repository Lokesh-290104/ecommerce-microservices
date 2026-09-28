package dev.lokesh.shop.product;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;

/**
 * Base for *IT tests: one real MySQL 8.4 per test JVM, set up by the repo's own
 * docker/mysql/init/01-databases.sh with the same image and settings as docker-compose.yml.
 * The app connects as product_svc to products_db, exactly as in compose. Subclasses add
 * {@code @Testcontainers(disabledWithoutDocker = true)} so they skip when Docker is absent.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class MySqlTestSupport {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final List<String> SERVICES = List.of("user", "product", "order", "payment");

    static final MySQLContainer MYSQL = withServicePasswords(new MySQLContainer("mysql:8.4"))
            .withEnv("MYSQL_ROOT_HOST", "localhost") // as in docker-compose.yml
            .withCopyFileToContainer(
                    MountableFile.forHostPath(ROOT.resolve("docker/mysql/init/01-databases.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-databases.sh");

    static {
        // Started once and shared by every IT class (and their cached Spring context);
        // Testcontainers' Ryuk removes it when the JVM exits.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url("products_db"));
        registry.add("spring.datasource.username", () -> "product_svc");
        registry.add("spring.datasource.password", () -> password("product"));
    }

    static String password(String svc) {
        return svc + "_svc_it_pw";
    }

    static String url(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database;
    }

    private static MySQLContainer withServicePasswords(MySQLContainer container) {
        SERVICES.forEach(svc -> container.withEnv(svc.toUpperCase() + "_SVC_DB_PASSWORD", password(svc)));
        return container;
    }
}
