package dev.lokesh.shop.product.api;

import dev.lokesh.shop.product.api.ProductDtos.CreateProductRequest;
import dev.lokesh.shop.product.api.ProductDtos.ProductResponse;
import dev.lokesh.shop.product.api.ProductDtos.UpdateProductRequest;
import dev.lokesh.shop.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Catalog API. Authentication for the write endpoints arrives in step 4 (JWT). */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse product = productService.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(product.id()).toUri();
        return ResponseEntity.created(location).body(product);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable long id) {
        return productService.get(id);
    }

    /** Optionally filtered by category; always sorted by id so pages are stable. */
    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(productService.list(categoryId, PageRequest.of(page, size, Sort.by("id"))));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable long id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, request);
    }
}
