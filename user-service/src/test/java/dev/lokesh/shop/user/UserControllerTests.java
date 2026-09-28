package dev.lokesh.shop.user;

import dev.lokesh.shop.user.api.UserController;
import dev.lokesh.shop.user.error.ApiException;
import dev.lokesh.shop.user.error.ErrorCode;
import dev.lokesh.shop.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only (no database): validation and the ProblemDetail error contract. */
@WebMvcTest(UserController.class)
class UserControllerTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserService userService;

    @Test
    void invalidRegistrationListsEveryBadField() throws Exception {
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
    void businessErrorsCarryTheirCodeAndStatus() throws Exception {
        when(userService.get(anyLong())).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND, "No user with id 7."));
        when(userService.register(any())).thenThrow(new ApiException(ErrorCode.EMAIL_TAKEN, "taken"));

        mvc.perform(get("/api/users/7"))
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
        mvc.perform(get("/api/users/abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users").param("page", "-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }
}
