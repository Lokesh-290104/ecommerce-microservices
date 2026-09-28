-- Step 8 fix (measured separately from the v1 baseline, design D26): "my orders, newest first"
-- filters by user_id and sorts by created_at, so one index serves both.
CREATE INDEX ix_orders_user_created ON orders (user_id, created_at);
