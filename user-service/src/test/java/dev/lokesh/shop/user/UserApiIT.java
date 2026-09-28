package dev.lokesh.shop.user;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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

/** The users API end to end against real MySQL, called with tokens from the real login endpoint. */
@Testcontainers(disabledWithoutDocker = true)
class UserApiIT extends MySqlTestSupport {

    private static final String PASSWORD = "password-123";

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
        mvc.perform(get("/api/users/{id}", id).header(HttpHeaders.AUTHORIZATION, login(email, "correct-horse-battery")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void emailIsUniqueIgnoringCase() throws Exception {
        String email = uniqueEmail();
        register(email, "First", PASSWORD);

        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "Second", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void unknownUserIs404ForInternalCallers() throws Exception {
        mvc.perform(get("/api/users/{id}", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.internal())))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateChangesNameAndEmailButNotToATakenEmail() throws Exception {
        String email = uniqueEmail();
        long id = register(email, "Old Name", PASSWORD);
        String token = login(email, PASSWORD);
        String takenEmail = uniqueEmail();
        register(takenEmail, "Other", PASSWORD);
        String newEmail = uniqueEmail();

        mvc.perform(put("/api/users/{id}", id).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail + "\",\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.name").value("New Name"));

        mvc.perform(put("/api/users/{id}", id).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + takenEmail + "\",\"name\":\"New Name\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void deleteIsSoftAndHidesTheUserEverywhere() throws Exception {
        String email = uniqueEmail();
        long id = register(email, "Gone Soon", PASSWORD);
        String token = login(email, PASSWORD);

        mvc.perform(delete("/api/users/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());

        // The still-unexpired token now points at a deleted user.
        mvc.perform(get("/api/users/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        // The row is kept (soft delete), its email stays taken, and it can no longer log in.
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, id)).isFalse();
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body(email, "Again", PASSWORD)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void listIsPagedSortedByIdAndSkipsDeletedUsers() throws Exception {
        long a = register(uniqueEmail(), "A", PASSWORD);
        String bEmail = uniqueEmail();
        long b = register(bEmail, "B", PASSWORD);
        long c = register(uniqueEmail(), "C", PASSWORD);
        mvc.perform(delete("/api/users/{id}", b).header(HttpHeaders.AUTHORIZATION, login(bEmail, PASSWORD)))
                .andExpect(status().isNoContent());
        String token = TestTokens.bearer(TestTokens.user(a));

        String json = mvc.perform(get("/api/users").param("size", "100").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids = JsonPath.read(json, "$.content[*].id");
        assertThat(ids).contains((int) a, (int) c).doesNotContain((int) b).isSorted();

        mvc.perform(get("/api/users").param("size", "1").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/users").param("size", "101").header(HttpHeaders.AUTHORIZATION, token))
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

    /** Logs in through the real endpoint and returns an Authorization header value. */
    private String login(String email, String password) throws Exception {
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(json, "$.accessToken");
    }

    static String body(String email, String name, String password) {
        return "{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"password\":\"" + password + "\"}";
    }

    static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
