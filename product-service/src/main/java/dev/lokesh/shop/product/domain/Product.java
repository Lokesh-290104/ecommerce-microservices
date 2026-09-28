package dev.lokesh.shop.product.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Catalog data only. Stock is in {@link Inventory}, so catalog edits never race stock changes. */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Optimistic lock: a PUT carrying a stale version gets 409 instead of overwriting. */
    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<ProductImage> images = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
        // for JPA
    }

    public Product(Category category, String name, String description, BigDecimal price, List<String> imageUrls) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        replaceImages(imageUrls);
    }

    /**
     * Replaces every catalog field. Setting updatedAt always makes the row dirty, so the
     * version increases on every update, including one that only changes images (the images
     * collection is on the other side of the relation and would not bump it by itself).
     */
    public void update(Category category, String name, String description, BigDecimal price, List<String> imageUrls) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        replaceImages(imageUrls);
        this.updatedAt = Instant.now();
    }

    private void replaceImages(List<String> imageUrls) {
        images.clear();
        for (int i = 0; i < imageUrls.size(); i++) {
            images.add(new ProductImage(this, imageUrls.get(i), i));
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getVersion() {
        return version;
    }

    public List<ProductImage> getImages() {
        return images;
    }
}
