-- One row per checkout attempt. (user_id, idempotency_key) is unique, so a double-clicked
-- "Buy" or a client retry finds the first order instead of creating a second one.
CREATE TABLE orders (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    user_id          BIGINT         NOT NULL,
    idempotency_key  VARCHAR(100)   NOT NULL,
    -- SHA-256 of the canonical request: the same key with a different body is refused (422).
    request_hash     CHAR(64)       NOT NULL,
    -- Kept so the reconciler can re-send the charge if the first attempt never reached payments.
    payment_token    VARCHAR(200)   NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    cancel_reason    VARCHAR(30),
    -- Stock work still owed to product-service after a final state (retried by the reconciler).
    inventory_action VARCHAR(20)    NOT NULL,
    total_amount     DECIMAL(12, 2),
    -- HTTP status of the first answer, so replays return the same code (design D15).
    response_status  INT,
    pending_since    DATETIME(6),
    version          BIGINT         NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    updated_at       DATETIME(6)    NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_orders_status CHECK (status IN ('CREATED', 'PAYMENT_PENDING', 'PAID', 'PAYMENT_FAILED', 'CANCELLED')),
    CONSTRAINT ck_orders_inventory_action CHECK (inventory_action IN ('NONE', 'RELEASE_PENDING', 'COMMIT_PENDING', 'DONE', 'FAILED'))
) ENGINE = InnoDB;

-- The reconciler's two scans (design D20). The (user_id, created_at) index for the order
-- listing is deliberately NOT here yet: step 8 measures the listing first (design D26).
CREATE INDEX ix_orders_status_pending ON orders (status, pending_since);
CREATE INDEX ix_orders_inventory_action ON orders (inventory_action);

CREATE TABLE order_items (
    order_id   BIGINT         NOT NULL,
    product_id BIGINT         NOT NULL,
    quantity   INT            NOT NULL,
    -- Filled from the reservation's prices once stock is held.
    unit_price DECIMAL(12, 2),
    CONSTRAINT pk_order_items PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- Every status change, for support and for the demo ("why is my order pending?").
CREATE TABLE order_status_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_id    BIGINT      NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    reason      VARCHAR(100),
    changed_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_order_status_history PRIMARY KEY (id),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB;
