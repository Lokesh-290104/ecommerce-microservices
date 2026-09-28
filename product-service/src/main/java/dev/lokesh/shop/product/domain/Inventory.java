package dev.lokesh.shop.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Stock for one product. Reservations (step 5) change {@code reserved} with conditional
 * UPDATEs, never through the catalog's optimistic lock.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    protected Inventory() {
        // for JPA
    }

    public Inventory(Long productId, int onHand) {
        this.productId = productId;
        this.onHand = onHand;
    }

    public Long getProductId() {
        return productId;
    }

    public int available() {
        return onHand - reserved;
    }
}
