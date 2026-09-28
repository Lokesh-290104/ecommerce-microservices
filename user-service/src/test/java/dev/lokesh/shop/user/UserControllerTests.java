package dev.lokesh.shop.user;

import dev.lokesh.shop.user.api.UserController;
import dev.lokesh.shop.user.domain.User;
import dev.lokesh.shop.user.error.ApiException;
import dev.lokesh.shop.user.error.ErrorCode;
import dev.lokesh.shop.user.security.JwtKeys;
import dev.lokesh.shop.user.security.ProblemAuthHandlers;
import dev.lokesh.shop.user.security.SecurityConfig;
import dev.lokesh.shop.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only (no database): validation, security rules and the ProblemDetail error contract. */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtKeys.class, ProblemAuthHandlers.class})
class UserControllerTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserService userService;

    @Test
    void registrationIsPublicAndListsEveryBadField() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password", "name")));
        verifyNoInteractions(userService);
    }

    @Test
    void malformedJsonIs400Problem() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")));
    }

    @Test
    void missingOrInvalidTokenIs401Problem() throws Exception {
        mvc.perform(get("/api/users/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")));
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    @Test
    void aUserCanOnlyReadAndChangeThemselves() throws Exception {
        String user8 = TestTokens.bearer(TestTokens.user(8));
        String body = "{\"email\":\"a@example.com\",\"name\":\"A\"}";

        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION, user8))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/api/users/7").header(HttpHeaders.AUTHORIZATION, user8)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/7").header(HttpHeaders.AUTHORIZATION, user8))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    @Test
    void internalServiceTokensMayReadAnyUserButNotChangeThem() throws Exception {
        when(userService.get(7)).thenReturn(new User("ada@example.com", "hash", "Ada"));
        String internal = TestTokens.bearer(TestTokens.internal());

        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION, internal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"));
        mvc.perform(delete("/api/users/7").header(HttpHeaders.AUTHORIZATION, internal))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokensFromUntrustedIssuersOrOtherKeysAreRejected() throws Exception {
        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(TestTokens.fromIssuer("someone-else", 7))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(TestTokens.signedWith("another-secret-that-is-long-enough-0123", 7))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(TestTokens.expiredUser(7))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    @Test
    void businessErrorsCarryTheirCodeAndStatus() throws Exception {
        when(userService.get(anyLong())).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND, "No user with id 7."));
        when(userService.register(any())).thenThrow(new ApiException(ErrorCode.EMAIL_TAKEN, "taken"));

        mvc.perform(get("/api/users/7").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.user(7))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("User not found"))
                .andExpect(jsonPath("$.detail").value("No user with id 7."));
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"password-123\",\"name\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void badPathAndPagingParametersAre400() throws Exception {
        String token = TestTokens.bearer(TestTokens.user(7));
        mvc.perform(get("/api/users/abc").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users").param("size", "0").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/users").param("page", "-1").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }
}
