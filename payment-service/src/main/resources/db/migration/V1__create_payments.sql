-- At most one payment per order (design D12). The UNIQUE key is what makes charging
-- idempotent under concurrency: a checkout retry and a reconciler re-POST can race, and the
-- loser's INSERT fails and re-reads the winner instead of charging twice.
CREATE TABLE payments (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    order_id   BIGINT         NOT NULL,
    amount     DECIMAL(12, 2) NOT NULL,
    status     VARCHAR(16)    NOT NULL,
    created_at DATETIME(6)    NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uk_payments_order UNIQUE (order_id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('APPROVED', 'DECLINED'))
) ENGINE = InnoDB;
