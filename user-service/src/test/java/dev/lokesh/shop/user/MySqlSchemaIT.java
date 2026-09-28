package dev.lokesh.shop.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers what the offline tests cannot: the service connects as its own user, the db health
 * contributor is UP, and MySQL itself enforces database-per-service.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlSchemaIT extends MySqlTestSupport {

    @Autowired
    MockMvc mvc;

    @Test
    void dbHealthIsUpAgainstRealMysql() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
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
}
