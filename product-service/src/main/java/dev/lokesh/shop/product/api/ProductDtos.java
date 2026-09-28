package dev.lokesh.shop.product.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.math.BigDecimal;
import java.util.List;

/** Request and response bodies for /api/products. */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotNull @Positive Long categoryId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull @PositiveOrZero @Max(1_000_000) Integer initialStock,
            @Size(max = 10) List<@NotBlank @URL @Size(max = 500) String> imageUrls) {
    }

    /** Catalog fields only: stock changes go through inventory reservations (step 5). */
    public record UpdateProductRequest(
            @NotNull @Positive Long categoryId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @Size(max = 10) List<@NotBlank @URL @Size(max = 500) String> imageUrls,
            /* The version the client last read; a stale one is rejected with 409. */
            @NotNull @PositiveOrZero Long version) {
    }

    public record ProductResponse(
            Long id,
            Long categoryId,
            String categoryName,
            String name,
            String description,
            BigDecimal price,
            List<String> imageUrls,
            int available,
            long version) {
    }
}
