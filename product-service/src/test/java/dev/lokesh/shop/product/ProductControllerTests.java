package dev.lokesh.shop.product;

import dev.lokesh.shop.product.api.ProductController;
import dev.lokesh.shop.product.error.ApiException;
import dev.lokesh.shop.product.error.ErrorCode;
import dev.lokesh.shop.product.security.JwtKeys;
import dev.lokesh.shop.product.security.ProblemAuthHandlers;
import dev.lokesh.shop.product.security.SecurityConfig;
import dev.lokesh.shop.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only (no database): validation, security rules and the ProblemDetail error contract. */
@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, JwtKeys.class, ProblemAuthHandlers.class})
class ProductControllerTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProductService productService;

    private static final String USER = TestTokens.bearer(TestTokens.user(1));

    @Test
    void writesNeedATokenAndInventoryNeedsAServiceToken() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // Inventory endpoints (step 5) are internal: a user token is refused before any handler runs.
        mvc.perform(post("/api/inventory/reservations").header(HttpHeaders.AUTHORIZATION, USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/inventory/reservations"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(productService);
    }

    @Test
    void invalidProductListsEveryBadField() throws Exception {
        mvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":0,\"name\":\"\",\"price\":0,\"initialStock\":-1,"
                                + "\"imageUrls\":[\"not a url\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field",
                        hasItems("categoryId", "name", "price", "initialStock", "imageUrls[0]")));
        verifyNoInteractions(productService);
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejected() throws Exception {
        mvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":1,\"name\":\"x\",\"price\":1.999,\"initialStock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void updateWithoutAVersionIsRejected() throws Exception {
        mvc.perform(put("/api/products/1").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":1,\"name\":\"x\",\"price\":1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        verifyNoInteractions(productService);
    }

    @Test
    void businessErrorsAndLostRacesCarryTheirCodes() throws Exception {
        when(productService.get(anyLong())).thenThrow(new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "missing"));
        when(productService.update(anyLong(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", 1L));

        mvc.perform(get("/api/products/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mvc.perform(put("/api/products/1").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":1,\"name\":\"x\",\"price\":1.00,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void badPagingParametersAre400() throws Exception {
        mvc.perform(get("/api/products").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("categoryId", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }
}
