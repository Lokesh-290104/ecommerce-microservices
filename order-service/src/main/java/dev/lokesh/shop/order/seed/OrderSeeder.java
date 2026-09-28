package dev.lokesh.shop.order.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Benchmark data (step 8): 50,000 orders for users 1..1000 (50 each, so pages 0..2 of 20 are
 * full), 1-5 items each, only in final states (about 80% PAID, 10% CANCELLED, 10%
 * PAYMENT_FAILED) with no stock work owed (design D17), so the reconciler has nothing to do.
 * Deterministic: Random(42). Only in the "seed" profile, and only into an empty table.
 */
@Component
@Profile("seed")
public class OrderSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderSeeder.class);
    public static final int ORDERS = 50_000;
    public static final int USERS = 1_000;
    public static final int PRODUCTS = 10_000;
    private static final int BATCH = 2_000;

    private final JdbcTemplate jdbc;

    public OrderSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Same formula as product-service's seeder. */
    static BigDecimal price(long productId) {
        return BigDecimal.valueOf(productId * 37 % 9_000 + 100, 2);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class) > 0) {
            log.warn("orders is not empty; seeding skipped (reset with docker compose down -v)");
            return;
        }
        Random random = new Random(42);
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Object[]> orders = new ArrayList<>();
        List<Object[]> items = new ArrayList<>();
        List<Object[]> history = new ArrayList<>();
        for (long id = 1; id <= ORDERS; id++) {
            Timestamp created = Timestamp.from(base.minus(random.nextInt(365 * 24 * 60), ChronoUnit.MINUTES));
            Set<Long> products = new LinkedHashSet<>();
            int lines = 1 + random.nextInt(5);
            while (products.size() < lines) {
                products.add(1L + random.nextInt(PRODUCTS));
            }
            double roll = random.nextDouble();
            String status = roll < 0.8 ? "PAID" : roll < 0.9 ? "CANCELLED" : "PAYMENT_FAILED";
            boolean priced = !status.equals("CANCELLED"); // cancelled for stock: never reserved, never priced
            BigDecimal total = BigDecimal.ZERO;
            for (long product : products) {
                int quantity = 1 + random.nextInt(3);
                items.add(new Object[]{id, product, quantity, priced ? price(product) : null});
                total = total.add(price(product).multiply(BigDecimal.valueOf(quantity)));
            }
            int httpStatus = switch (status) {
                case "PAID" -> 201;
                case "CANCELLED" -> 409;
                default -> 402;
            };
            orders.add(new Object[]{id, (id - 1) % USERS + 1, "seed-" + id, "0".repeat(64), "tok_visa", status,
                    status.equals("CANCELLED") ? "OUT_OF_STOCK" : null, status.equals("CANCELLED") ? null : total,
                    httpStatus, created, created});
            history.add(new Object[]{id, null, "CREATED", null, created});
            if (status.equals("CANCELLED")) {
                history.add(new Object[]{id, "CREATED", "CANCELLED", "OUT_OF_STOCK", created});
            } else {
                history.add(new Object[]{id, "CREATED", "PAYMENT_PENDING", "stock reserved", created});
                history.add(new Object[]{id, "PAYMENT_PENDING", status, null, created});
            }
            if (id % BATCH == 0) {
                flush(orders, items, history);
            }
        }
        flush(orders, items, history);
        log.info("Seeded {} orders", ORDERS);
    }

    private void flush(List<Object[]> orders, List<Object[]> items, List<Object[]> history) {
        jdbc.batchUpdate("INSERT INTO orders (id, user_id, idempotency_key, request_hash, payment_token, status, "
                + "cancel_reason, inventory_action, total_amount, response_status, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'DONE', ?, ?, 0, ?, ?)", orders);
        jdbc.batchUpdate("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)", items);
        jdbc.batchUpdate("INSERT INTO order_status_history (order_id, from_status, to_status, reason, changed_at) "
                + "VALUES (?, ?, ?, ?, ?)", history);
        orders.clear();
        items.clear();
        history.clear();
    }
}
