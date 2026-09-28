package dev.lokesh.shop.user.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark data (step 8): users 1..1000, all with the password "bench-password".
 * Only in the "seed" profile, and only into an empty table.
 */
@Component
@Profile("seed")
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);
    public static final int USERS = 1_000;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public UserSeeder(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class) > 0) {
            log.warn("users is not empty; seeding skipped (reset with docker compose down -v)");
            return;
        }
        String hash = passwords.encode("bench-password"); // one hash: BCrypt x1000 would dominate seeding
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>(USERS);
        for (int id = 1; id <= USERS; id++) {
            rows.add(new Object[]{id, "user" + id + "@bench.example.com", hash, "Bench User " + id, now, now});
        }
        jdbc.batchUpdate("INSERT INTO users (id, email, password_hash, name, active, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, TRUE, ?, ?)", rows);
        log.info("Seeded {} users", USERS);
    }
}
