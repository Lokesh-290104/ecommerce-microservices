#!/bin/bash
# Runs once, on the first start of an empty MySQL volume (docker-entrypoint-initdb.d).
# Database per service: each service gets its own schema and a user that can touch only
# that schema, so a cross-service join is impossible, not just discouraged.
set -euo pipefail

# The passwords are spliced into SQL string literals below, so refuse the characters
# that would break out of them. Failing here is loud; a half-run init is not, because
# MySQL skips this directory on every later start of the same volume.
for var in USER_SVC_DB_PASSWORD PRODUCT_SVC_DB_PASSWORD ORDER_SVC_DB_PASSWORD PAYMENT_SVC_DB_PASSWORD; do
    case "${!var-}" in
        "" | *"'"* | *\\*)
            echo "01-databases.sh: $var must be non-empty and contain no ' or \\." \
                 "Fix it in .env, then run: docker compose down -v (this volume is now half-initialized)" >&2
            exit 1 ;;
    esac
done

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
SQL
