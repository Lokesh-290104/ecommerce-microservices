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

On Windows with Docker in WSL, run the compose commands inside WSL, e.g. from Git Bash:
`wsl.exe --cd "$(pwd -W)" docker compose up -d --wait`.

MySQL is published on `127.0.0.1:3307` (override with `MYSQL_HOST_PORT`). Local dev
passwords live in `.env.example`; copy it to `.env` to change them. Passwords are set only
when the MySQL volume is first created, so after changing one run `docker compose down -v`
(otherwise MySQL stays unhealthy and no service starts). A password must not contain `'`
or `\`, and a literal `$` is written as `$$`.

## API (so far)

| Service | Endpoint | Notes |
|---|---|---|
| user | `POST /api/users` | Register; password stored as a BCrypt hash; duplicate email (any case) -> 409 |
| user | `GET /api/users/{id}` | 404 ProblemDetail if missing or deleted |
| user | `GET /api/users?page&size` | Paged, sorted by id, size <= 100 |
| user | `PUT /api/users/{id}` | Change email and name |
| user | `DELETE /api/users/{id}` | Soft delete (`active=false`); the email stays taken |
| product | `POST /api/products` | Creates the product and its inventory row in one transaction |
| product | `GET /api/products/{id}` | `available = on_hand - reserved` |
| product | `GET /api/products?categoryId&page&size` | Paged, sorted by id |
| product | `PUT /api/products/{id}` | Catalog fields only; send the `version` you read, stale -> 409 |

Errors are RFC 7807 `application/problem+json` bodies with a machine-readable `code`
(e.g. `EMAIL_TAKEN`, `VERSION_CONFLICT`) and, for validation, a per-field `errors` list.
Schemas are created by Flyway migrations; Hibernate only validates them.

## Tests

`./mvnw verify` runs the unit tests (offline) and then the `*IT` integration tests, which
start a real MySQL 8.4 with [Testcontainers](https://testcontainers.com/) using the same init
script as compose. Without a reachable Docker daemon the integration tests are skipped, not
failed. CI runs everything on every push and pull request.

### Testcontainers on Windows with Docker in WSL

The JVM runs on Windows but Docker lives in WSL, so the WSL daemon also listens on a TCP port
bound to loopback only. In WSL:

```bash
sudo mkdir -p /etc/systemd/system/docker.service.d
printf '[Service]\nExecStart=\nExecStart=/usr/bin/dockerd -H fd:// -H tcp://127.0.0.1:2375 --containerd=/run/containerd/containerd.sock\n' \
  | sudo tee /etc/systemd/system/docker.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart docker
```

Then on Windows: `setx DOCKER_HOST tcp://localhost:2375` and open a new terminal.

> **Security:** this TCP socket has no authentication and is root-equivalent inside WSL.
> It is bound to `127.0.0.1` only, so nothing outside your machine can reach it, but any
> local process can. Never bind it to `0.0.0.0`. Keep WSL running during test runs (WSL
> shuts an idle VM down; `vmIdleTimeout` in `.wslconfig` controls that).

Release notes are in [CHANGELOG.md](CHANGELOG.md); deferred work is in [TODOS.md](TODOS.md).
