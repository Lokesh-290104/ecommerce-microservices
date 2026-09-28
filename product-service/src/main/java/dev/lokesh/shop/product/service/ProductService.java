package dev.lokesh.shop.product.service;

import dev.lokesh.shop.product.api.ProductDtos.CreateProductRequest;
import dev.lokesh.shop.product.api.ProductDtos.ProductResponse;
import dev.lokesh.shop.product.api.ProductDtos.UpdateProductRequest;
import dev.lokesh.shop.product.domain.Category;
import dev.lokesh.shop.product.domain.CategoryRepository;
import dev.lokesh.shop.product.domain.Inventory;
import dev.lokesh.shop.product.domain.InventoryRepository;
import dev.lokesh.shop.product.domain.Product;
import dev.lokesh.shop.product.domain.ProductImage;
import dev.lokesh.shop.product.domain.ProductRepository;
import dev.lokesh.shop.product.error.ApiException;
import dev.lokesh.shop.product.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final InventoryRepository inventory;

    public ProductService(ProductRepository products, CategoryRepository categories, InventoryRepository inventory) {
        this.products = products;
        this.categories = categories;
        this.inventory = inventory;
    }

    /** Creates the catalog row and its inventory row in one transaction. */
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product product = products.save(new Product(category(request.categoryId()), request.name().strip(),
                request.description(), request.price(), urls(request.imageUrls())));
        inventory.save(new Inventory(product.getId(), request.initialStock()));
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return toResponse(find(id));
    }

    /**
     * v1 listing, written the straightforward way on purpose (design D26): each product's
     * category, images and stock are loaded one product at a time while mapping, so a page of
     * 20 costs dozens of queries. Step 8 measures this baseline, then fixes it in separate commits.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Long categoryId, Pageable pageable) {
        Page<Product> page = categoryId == null
                ? products.findAll(pageable)
                : products.findByCategoryId(categoryId, pageable);
        return page.map(this::toResponse);
    }

    @Transactional
    public ProductResponse update(long id, UpdateProductRequest request) {
        Product product = find(id);
        if (product.getVersion() != request.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "Product " + id + " is at version "
                    + product.getVersion() + ", not " + request.version() + "; re-read it and retry.");
        }
        product.update(category(request.categoryId()), request.name().strip(), request.description(),
                request.price(), urls(request.imageUrls()));
        // Flush now so the incremented version (and a lost race, as a 409) shows up in this response.
        products.saveAndFlush(product);
        return toResponse(product);
    }

    private Product find(long id) {
        return products.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + id + "."));
    }

    private Category category(long id) {
        return categories.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.CATEGORY_NOT_FOUND, "No category with id " + id + "."));
    }

    private ProductResponse toResponse(Product product) {
        int available = inventory.findById(product.getId()).map(Inventory::available).orElse(0);
        return new ProductResponse(
                product.getId(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getImages().stream().map(ProductImage::getUrl).toList(),
                available,
                product.getVersion());
    }

    private static List<String> urls(List<String> imageUrls) {
        return imageUrls == null ? List.of() : imageUrls;
    }
}
