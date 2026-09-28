-- Step 8 fix (measured separately from the v1 baseline, design D26): the category listing
-- filters by category_id and sorts by id, so one index serves both the WHERE and the ORDER BY.
CREATE INDEX ix_products_category_id ON products (category_id, id);
