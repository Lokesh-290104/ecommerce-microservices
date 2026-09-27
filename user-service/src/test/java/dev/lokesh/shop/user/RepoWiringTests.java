package dev.lokesh.shop.user;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-file wiring for the whole repo, kept in one module on purpose: ports, jar names and
 * DB passwords are repeated across docker-compose.yml, Dockerfiles, application.yml files, POMs
 * and the MySQL init script, and nothing else fails if one copy drifts.
 * Reads the repo root as ".." from this module's basedir (Surefire's working directory).
 */
class RepoWiringTests {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern DEFAULT = Pattern.compile("^\\$\\{(\\w+):-(.*)}$");

    private static Map<String, Object> compose;
    private static String initScript;

    @BeforeAll
    static void load() throws IOException {
        compose = yaml(ROOT.resolve("docker-compose.yml"));
        initScript = Files.readString(ROOT.resolve("docker/mysql/init/01-databases.sh"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "user-service,    user,    USER_SVC_DB_PASSWORD",
            "product-service, product, PRODUCT_SVC_DB_PASSWORD",
            "order-service,   order,   ORDER_SVC_DB_PASSWORD",
            "payment-service, payment, PAYMENT_SVC_DB_PASSWORD"})
    void serviceWiringAgreesAcrossFiles(String service, String svc, String passwordVar) throws IOException {
        Path module = ROOT.resolve(service);
        Map<String, Object> appYml = yaml(module.resolve("src/main/resources/application.yml"));
        Object port = get(appYml, "server", "port");
        String ymlPort = port.toString();
        String dockerfile = Files.readString(module.resolve("Dockerfile"));
        Map<String, Object> svcCompose = get(compose, "services", service);

        // One port everywhere: application.yml, Dockerfile EXPOSE, compose mapping, compose healthcheck.
        assertEquals(ymlPort, match(dockerfile, "(?m)^EXPOSE\\s+(\\d+)\\s*$"), "Dockerfile EXPOSE");
        assertEquals(List.of("127.0.0.1:" + ymlPort + ":" + ymlPort), get(svcCompose, "ports"), "compose ports");
        List<?> healthTest = get(svcCompose, "healthcheck", "test");
        assertEquals("http://localhost:" + ymlPort + "/actuator/health", healthTest.get(healthTest.size() - 1),
                "compose healthcheck URL");

        // The Dockerfile copies the jar the POM actually produces (finalName = artifactId).
        String pom = Files.readString(module.resolve("pom.xml"));
        assertTrue(pom.contains("<finalName>${project.artifactId}</finalName>"), "pom finalName");
        String artifactId = match(pom.substring(pom.indexOf("</parent>")), "<artifactId>([^<]+)</artifactId>");
        assertEquals(service, artifactId);
        assertEquals("target/" + artifactId + ".jar", match(dockerfile, "(?m)^COPY\\s+(\\S+)\\s+app\\.jar\\s*$"));

        // Same dev password in: compose mysql env (used by the init script), compose service env,
        // application.yml default and .env.example.
        Map<String, Object> mysqlEnv = get(compose, "services", "mysql", "environment");
        String mysqlDefault = defaultOf((String) mysqlEnv.get(passwordVar), passwordVar);
        Map<String, Object> svcEnv = get(svcCompose, "environment");
        assertEquals(mysqlDefault, defaultOf((String) svcEnv.get("DB_PASSWORD"), passwordVar), "compose DB_PASSWORD");
        assertEquals("${DB_PASSWORD:" + mysqlDefault + "}", get(appYml, "spring", "datasource", "password"));
        assertEquals("${DB_USER:" + svc + "_svc}", get(appYml, "spring", "datasource", "username"));
        // (?m) + \r?$: a Windows checkout with autocrlf gives this file CRLF line endings.
        assertTrue(Pattern.compile("(?m)^" + passwordVar + "=" + Pattern.quote(mysqlDefault) + "\\r?$")
                .matcher(Files.readString(ROOT.resolve(".env.example"))).find(), ".env.example " + passwordVar);
        assertTrue(initScript.matches("(?s).*CREATE USER IF NOT EXISTS '" + svc
                + "_svc'@'%'\\s+IDENTIFIED BY '\\$\\{" + passwordVar + "}';.*"), "init CREATE USER " + svc);

        // application.yml points at the schema the init script grants this user.
        String url = get(appYml, "spring", "datasource", "url");
        assertEquals(svc + "s_db", url.substring(url.lastIndexOf('/') + 1));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"user-service", "product-service", "order-service", "payment-service"})
    void dbHostAndPortAgreeWithMysqlService(String service) throws IOException {
        // Host side: application.yml default port == compose's published MySQL port == .env.example.
        List<?> mysqlPorts = get(compose, "services", "mysql", "ports");
        Matcher m = Pattern.compile("^127\\.0\\.0\\.1:\\$\\{MYSQL_HOST_PORT:-(\\d+)}:(\\d+)$")
                .matcher(mysqlPorts.get(0).toString());
        assertTrue(m.matches(), mysqlPorts.toString());
        String hostPort = m.group(1);
        String containerPort = m.group(2);
        Map<String, Object> appYml = yaml(ROOT.resolve(service).resolve("src/main/resources/application.yml"));
        String url = get(appYml, "spring", "datasource", "url");
        assertTrue(url.startsWith("jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:" + hostPort + "}/"), url);
        assertTrue(Pattern.compile("(?m)^MYSQL_HOST_PORT=" + hostPort + "\\r?$")
                .matcher(Files.readString(ROOT.resolve(".env.example"))).find(), ".env.example MYSQL_HOST_PORT");

        // Inside compose: the service reaches the mysql service on its container port.
        Map<String, Object> env = get(compose, "services", service, "environment");
        assertEquals("mysql", env.get("DB_HOST"));
        assertEquals(containerPort, env.get("DB_PORT").toString());
    }

    @Test
    void mysqlHealthcheckLogsInAsTheLastUserInitCreates() {
        // A half-run init leaves later users missing; logging in as the last one makes
        // "healthy" mean "init finished". (mysqladmin ping passes even on access denied.)
        Matcher users = Pattern.compile("CREATE USER IF NOT EXISTS '(\\w+)'").matcher(initScript);
        String last = null;
        while (users.find()) {
            last = users.group(1);
        }
        String svc = last.substring(0, last.length() - "_svc".length());
        List<?> test = get(compose, "services", "mysql", "healthcheck", "test");
        String cmd = test.get(test.size() - 1).toString();
        // Same user, that user's own password var ($$ so compose leaves it for the container
        // shell) and the schema it was granted last.
        assertTrue(cmd.startsWith("mysql -h 127.0.0.1 -u" + last + " "), cmd);
        assertTrue(cmd.contains("-p\"$$" + svc.toUpperCase() + "_SVC_DB_PASSWORD\""), cmd);
        assertTrue(cmd.endsWith(" " + svc + "s_db"), cmd);
    }

    @Test
    void everyBuiltServiceKeepsTheMemoryCap() {
        // environment: in a service replaces the anchor's (shallow merge), so check the result.
        Map<String, Object> services = get(compose, "services");
        services.forEach((name, def) -> {
            Map<?, ?> service = (Map<?, ?>) def;
            if (service.containsKey("build")) {
                assertEquals("512m", service.get("mem_limit"), name + " mem_limit");
                Object opts = ((Map<?, ?>) service.get("environment")).get("JAVA_TOOL_OPTIONS");
                assertTrue(opts != null && opts.toString().contains("-XX:MaxRAMPercentage="),
                        name + " JAVA_TOOL_OPTIONS: " + opts);
            }
        });
    }

    @Test
    void initScriptGrantsEachUserOnlyItsOwnSchema() {
        Matcher m = Pattern.compile("GRANT\\s+(.+?)\\s+ON\\s+(\\S+)\\s+TO\\s+'(\\w+)'@'%'").matcher(initScript);
        List<String> grants = new ArrayList<>();
        while (m.find()) {
            grants.add(m.group(3) + " -> " + m.group(2));
        }
        assertEquals(List.of(
                "user_svc -> users_db.*",
                "product_svc -> products_db.*",
                "order_svc -> orders_db.*",
                "payment_svc -> payments_db.*"), grants);
        assertTrue(!initScript.contains("*.*"), "no global grants");
    }

    @Test
    void everyComposeServiceRestartsAfterDaemonRestart() {
        // Regression: mysql once lacked a restart policy, so after a WSL idle shutdown the
        // services came back but MySQL did not, and every health check reported db DOWN.
        Map<String, Object> services = get(compose, "services");
        services.forEach((name, def) ->
                assertEquals("unless-stopped", ((Map<?, ?>) def).get("restart"), name + " restart policy"));
    }

    @Test
    void everyPublishedComposePortBindsLoopbackOnly() {
        Map<String, Object> services = get(compose, "services");
        services.forEach((name, def) -> {
            Object ports = ((Map<?, ?>) def).get("ports");
            if (ports != null) {
                for (Object p : (List<?>) ports) {
                    assertTrue(p.toString().startsWith("127.0.0.1:"), name + " publishes " + p);
                }
            }
        });
    }

    private static String defaultOf(String value, String expectedVar) {
        Matcher m = DEFAULT.matcher(value);
        assertTrue(m.matches(), "expected ${VAR:-default}, got " + value);
        assertEquals(expectedVar, m.group(1));
        return m.group(2);
    }

    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "no match for " + regex);
        return m.group(1);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Map<String, Object> map, String... keys) {
        Object cur = map;
        for (String k : keys) {
            cur = ((Map<String, Object>) cur).get(k);
            assertTrue(cur != null, "missing key " + k + " in path " + String.join(".", keys));
        }
        return (T) cur;
    }

    private static Map<String, Object> yaml(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            return new Yaml().load(r);
        }
    }
}
