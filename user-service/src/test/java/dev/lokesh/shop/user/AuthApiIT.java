package dev.lokesh.shop.user;

import com.jayway.jsonpath.JsonPath;
import dev.lokesh.shop.user.security.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static dev.lokesh.shop.user.UserApiIT.body;
import static dev.lokesh.shop.user.UserApiIT.credentials;
import static dev.lokesh.shop.user.UserApiIT.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login and token handling end to end: real BCrypt, real signing, real verification. */
@Testcontainers(disabledWithoutDocker = true)
class AuthApiIT extends MySqlTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder decoder;

    @Test
    void loginIssuesA30MinuteUserTokenForThatUser() throws Exception {
        String email = uniqueEmail();
        long id = register(email);

        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email.toUpperCase(), "password-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = decoder.decode(JsonPath.read(json, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(id));
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(Tokens.USER_ISSUER);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("user");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(Tokens.isInternal(jwt)).isFalse(); // a user can never act as a service
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        String email = uniqueEmail();
        register(email);

        String wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(uniqueEmail(), "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical responses: login must not reveal which emails are registered.
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void anOverlongPasswordIsJustAFailedLogin() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(uniqueEmail(), "x".repeat(150))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        long id = register(email);
        String token = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, "password-123"))).andReturn().getResponse().getContentAsString(), "$.accessToken");

        // Swap the payload for one claiming another user, keeping the original signature.
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                        .replace("\"sub\":\"" + id + "\"", "\"sub\":\"1\"").getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        mvc.perform(get("/api/users/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysPublicForTheComposeHealthcheck() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private long register(String email) throws Exception {
        String json = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "Auth Test", "password-123")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
