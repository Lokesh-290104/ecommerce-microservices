package dev.lokesh.shop.product.seed;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark data (step 8): 50 categories, products 1..10000 (200 per category, so pages 0..9
 * of 20 are full), 5 images each, 1000 units of stock each. Deterministic: price = f(id).
 * Only in the "seed" profile, and only into an empty catalog.
 */
@Component
@Profile("seed")
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);
    public static final int CATEGORIES = 50;
    public static final int PRODUCTS = 10_000;
    public static final int IMAGES_PER_PRODUCT = 5;
    private static final int BATCH = 2_000;

    private final JdbcTemplate jdbc;

    public CatalogSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Same formula as order-service's seeder, so seeded orders carry the real prices. */
    public static BigDecimal price(long productId) {
        return BigDecimal.valueOf(productId * 37 % 9_000 + 100, 2);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class) > 0) {
            log.warn("products is not empty; seeding skipped (reset with docker compose down -v)");
            return;
        }
        List<Object[]> categories = new ArrayList<>();
        for (int id = 6; id <= CATEGORIES; id++) { // 1..5 come from the V2 migration
            categories.add(new Object[]{id, "Category " + id});
        }
        jdbc.batchUpdate("INSERT INTO categories (id, name) VALUES (?, ?)", categories);

        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> products = new ArrayList<>();
        List<Object[]> images = new ArrayList<>();
        List<Object[]> stock = new ArrayList<>();
        for (long id = 1; id <= PRODUCTS; id++) {
            products.add(new Object[]{id, (id - 1) % CATEGORIES + 1, "Product " + id, price(id), now, now});
            for (int k = 0; k < IMAGES_PER_PRODUCT; k++) {
                images.add(new Object[]{id, "https://img.example.com/p" + id + "/" + k + ".jpg", k});
            }
            stock.add(new Object[]{id, 1_000});
            if (id % BATCH == 0) {
                flush(products, images, stock);
            }
        }
        flush(products, images, stock);
        log.info("Seeded {} categories, {} products, {} images", CATEGORIES, PRODUCTS, PRODUCTS * IMAGES_PER_PRODUCT);
    }

    private void flush(List<Object[]> products, List<Object[]> images, List<Object[]> stock) {
        jdbc.batchUpdate("INSERT INTO products (id, category_id, name, price, version, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 0, ?, ?)", products);
        jdbc.batchUpdate("INSERT INTO product_images (product_id, url, position) VALUES (?, ?, ?)", images);
        jdbc.batchUpdate("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", stock);
        products.clear();
        images.clear();
        stock.clear();
    }
}
