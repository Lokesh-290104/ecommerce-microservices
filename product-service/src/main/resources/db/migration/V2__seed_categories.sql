-- There is no categories API; the catalog starts with a fixed set. The benchmark seeder
-- (step 8) adds more in its own profile.
INSERT INTO categories (id, name) VALUES
    (1, 'Electronics'),
    (2, 'Books'),
    (3, 'Home & Kitchen'),
    (4, 'Clothing'),
    (5, 'Sports');
