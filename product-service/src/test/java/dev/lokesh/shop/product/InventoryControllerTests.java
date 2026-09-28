package dev.lokesh.shop.product;

import dev.lokesh.shop.product.api.InventoryController;
import dev.lokesh.shop.product.error.ApiException;
import dev.lokesh.shop.product.error.ErrorCode;
import dev.lokesh.shop.product.security.JwtKeys;
import dev.lokesh.shop.product.security.ProblemAuthHandlers;
import dev.lokesh.shop.product.security.SecurityConfig;
import dev.lokesh.shop.product.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only: request validation for reservations (the state machine is covered by ReservationApiIT). */
@WebMvcTest(InventoryController.class)
@Import({SecurityConfig.class, JwtKeys.class, ProblemAuthHandlers.class})
class InventoryControllerTests {

    private static final String INTERNAL = TestTokens.bearer(TestTokens.internal());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReservationService reservations;

    @Test
    void invalidReservationRequestsAreRejectedBeforeTouchingStock() throws Exception {
        mvc.perform(post("/api/inventory/reservations").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":0,\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("orderId", "items")));
        mvc.perform(post("/api/inventory/reservations").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[0].quantity"));
        verifyNoInteractions(reservations);
    }

    @Test
    void stateErrorsKeepTheirCodes() throws Exception {
        when(reservations.reserve(anyLong(), anyList()))
                .thenThrow(new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock"));
        when(reservations.commit(anyLong()))
                .thenThrow(new ApiException(ErrorCode.RESERVATION_NOT_ACTIVE, "nothing to commit"));

        mvc.perform(post("/api/inventory/reservations").header(HttpHeaders.AUTHORIZATION, INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(post("/api/inventory/reservations/1/commit").header(HttpHeaders.AUTHORIZATION, INTERNAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
    }
}
