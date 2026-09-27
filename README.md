# E-Commerce Microservices: Resilient Checkout

Four Spring Boot 4.1 services (users, products, orders, payments) on Java 21, each with its
own MySQL schema, built so that checkout survives a payment-service outage without losing
stock or double-charging. The design is in
[docs/designs/resilient-checkout-microservices.md](docs/designs/resilient-checkout-microservices.md).

> Work in progress. So far this is the scaffold: the four services, Docker images, and a
> compose stack whose health checks are green. Features land step by step (see the
> design doc's Next Steps).

| Service | Port | Schema |
|---|---|---|
| user-service | 8081 | `users_db` |
| product-service | 8082 | `products_db` |
| order-service | 8083 | `orders_db` |
| payment-service | 8084 | `payments_db` |

Each service connects as its own MySQL user that can only access its own schema, so
cross-service joins are impossible rather than merely discouraged.

## Run locally

Requires JDK 21 (`JAVA_HOME`) and Docker with Compose v2. On Windows the JDK can be on
Windows and Docker inside WSL; `scripts/build.sh` handles both.

```bash
scripts/build.sh          # ./mvnw package (runs tests), then docker compose build
docker compose up -d --wait
curl localhost:8081/actuator/health
```

MySQL is published on `127.0.0.1:3307` (override with `MYSQL_HOST_PORT`). Local dev
passwords live in `.env.example`; copy it to `.env` to change them.

Tests only: `./mvnw verify`
