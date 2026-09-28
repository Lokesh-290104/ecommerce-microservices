-- One reservation per order (order_id is the idempotency key). Status moves only
-- RESERVED -> COMMITTED (paid) or RESERVED -> RELEASED (payment failed / timed out).
-- A RELEASED row with no items is a tombstone: a release arrived before the reserve, and the
-- tombstone makes that late reserve fail instead of holding stock nobody will pay for.
CREATE TABLE reservations (
    order_id   BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_reservations PRIMARY KEY (order_id),
    CONSTRAINT ck_reservations_status CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED'))
) ENGINE = InnoDB;

-- unit_price is captured at reserve time, so a retried reserve returns the same prices even if
-- the catalog price changed in between.
CREATE TABLE reservation_items (
    order_id   BIGINT         NOT NULL,
    product_id BIGINT         NOT NULL,
    quantity   INT            NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    CONSTRAINT pk_reservation_items PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_reservation_items_reservation FOREIGN KEY (order_id) REFERENCES reservations (order_id),
    CONSTRAINT fk_reservation_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_reservation_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;
