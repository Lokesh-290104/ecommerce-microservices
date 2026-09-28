package dev.lokesh.shop.user;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The users API end to end against real MySQL (Flyway schema, unique email, soft delete). */
@Testcontainers(disabledWithoutDocker = true)
class UserApiIT extends MySqlTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registerStoresABcryptHashAndNeverReturnsIt() throws Exception {
        String email = uniqueEmail();

        long id = register(email, "Ada Lovelace", "correct-horse-battery");

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);
        assertThat(hash).startsWith("$2").isNotEqualTo("correct-horse-battery");
        mvc.perform(get("/api/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void emailIsUniqueIgnoringCase() throws Exception {
        String email = uniqueEmail();
        register(email, "First", "password-123");

        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "Second", "password-123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void unknownUserIs404Problem() throws Exception {
        mvc.perform(get("/api/users/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateChangesNameAndEmailButNotToATakenEmail() throws Exception {
        long id = register(uniqueEmail(), "Old Name", "password-123");
        String takenEmail = uniqueEmail();
        register(takenEmail, "Other", "password-123");
        String newEmail = uniqueEmail();

        mvc.perform(put("/api/users/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail + "\",\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.name").value("New Name"));

        mvc.perform(put("/api/users/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + takenEmail + "\",\"name\":\"New Name\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void deleteIsSoftAndHidesTheUserEverywhere() throws Exception {
        String email = uniqueEmail();
        long id = register(email, "Gone Soon", "password-123");

        mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/users/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/users/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail() + "\",\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        // The row is kept (soft delete) and its email stays taken.
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, id)).isFalse();
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "Again", "password-123")))
                .andExpect(status().isConflict());
    }

    @Test
    void listIsPagedSortedByIdAndSkipsDeletedUsers() throws Exception {
        long a = register(uniqueEmail(), "A", "password-123");
        long b = register(uniqueEmail(), "B", "password-123");
        long c = register(uniqueEmail(), "C", "password-123");
        mvc.perform(delete("/api/users/{id}", b)).andExpect(status().isNoContent());

        String json = mvc.perform(get("/api/users").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids = JsonPath.read(json, "$.content[*].id");
        assertThat(ids).contains((int) a, (int) c).doesNotContain((int) b).isSorted();

        mvc.perform(get("/api/users").param("size", "1"))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/users").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    private long register(String email, String name, String password) throws Exception {
        String json = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, name, password)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/users/")))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String body(String email, String name, String password) {
        return "{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"password\":\"" + password + "\"}";
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
