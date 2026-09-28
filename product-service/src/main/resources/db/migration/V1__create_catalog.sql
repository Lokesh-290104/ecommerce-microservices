CREATE TABLE categories (
    id   BIGINT       NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name)
) ENGINE = InnoDB;

-- Deliberately no secondary index on (category_id, id) yet: the listing endpoint is measured
-- first and indexed as a separate, benchmarked change (design D26).
CREATE TABLE products (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    category_id BIGINT         NOT NULL,
    name        VARCHAR(200)   NOT NULL,
    description VARCHAR(2000),
    price       DECIMAL(12, 2) NOT NULL,
    version     BIGINT         NOT NULL,
    created_at  DATETIME(6)    NOT NULL,
    updated_at  DATETIME(6)    NOT NULL,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_products_price CHECK (price > 0)
) ENGINE = InnoDB;

CREATE TABLE product_images (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    product_id BIGINT       NOT NULL,
    url        VARCHAR(500) NOT NULL,
    position   INT          NOT NULL,
    CONSTRAINT pk_product_images PRIMARY KEY (id),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE = InnoDB;

-- Stock lives apart from the catalog row, so editing a product (optimistic @Version on
-- products) never conflicts with reservations changing stock. available = on_hand - reserved.
CREATE TABLE inventory (
    product_id BIGINT NOT NULL,
    on_hand    INT    NOT NULL,
    reserved   INT    NOT NULL DEFAULT 0,
    CONSTRAINT pk_inventory PRIMARY KEY (product_id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_inventory_counts CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
) ENGINE = InnoDB;
