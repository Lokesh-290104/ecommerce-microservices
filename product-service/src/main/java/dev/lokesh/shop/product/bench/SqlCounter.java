package dev.lokesh.shop.product.bench;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate sends on the current thread (one request). Registered
 * only in the "bench" profile (application-bench.yml); it never changes the SQL.
 */
public class SqlCounter implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    static void reset() {
        COUNT.get()[0] = 0;
    }

    static int count() {
        return COUNT.get()[0];
    }
}
