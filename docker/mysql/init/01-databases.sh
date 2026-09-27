#!/bin/bash
# Runs once, on the first start of an empty MySQL volume (docker-entrypoint-initdb.d).
# Database per service: each service gets its own schema and a user that can touch only
# that schema, so a cross-service join is impossible, not just discouraged.
set -euo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS users_db;
CREATE DATABASE IF NOT EXISTS products_db;
CREATE DATABASE IF NOT EXISTS orders_db;
CREATE DATABASE IF NOT EXISTS payments_db;

CREATE USER IF NOT EXISTS 'user_svc'@'%'    IDENTIFIED BY '${USER_SVC_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'product_svc'@'%' IDENTIFIED BY '${PRODUCT_SVC_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'order_svc'@'%'   IDENTIFIED BY '${ORDER_SVC_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'payment_svc'@'%' IDENTIFIED BY '${PAYMENT_SVC_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON users_db.*    TO 'user_svc'@'%';
GRANT ALL PRIVILEGES ON products_db.* TO 'product_svc'@'%';
GRANT ALL PRIVILEGES ON orders_db.*   TO 'order_svc'@'%';
GRANT ALL PRIVILEGES ON payments_db.* TO 'payment_svc'@'%';
FLUSH PRIVILEGES;
SQL
