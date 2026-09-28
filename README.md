# E-Commerce Microservices: Resilient Checkout

[![CI](https://github.com/Lokesh-290104/ecommerce-microservices/actions/workflows/ci.yml/badge.svg)](https://github.com/Lokesh-290104/ecommerce-microservices/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![MySQL 8.4](https://img.shields.io/badge/MySQL-8.4-4479A1)

Four Spring Boot microservices (users, products, orders, payments), each with its own MySQL
schema, designed so that **checkout survives a payment-service outage**: no lost orders, no
leaked stock and no double charges. Every behaviour is covered by tests that run against a real
MySQL, both locally and in CI.

## Architecture

```mermaid
flowchart LR
    client([Client]) --> user[user-service :8081]
    client --> product[product-service :8082]
    client --> order[order-service :8083]
    order -- RestClient --> user
    order -- "reserve / commit / release stock" --> product
    order -- "charge (idempotent per order)" --> payment[payment-service :8084]
    user --> udb[(users_db)]
    product --> pdb[(products_db)]
    order --> odb[(orders_db)]
    payment --> paydb[(payments_db)]
```

One MySQL 8.4 container holds four schemas. Each service logs in as its own MySQL user that is
granted only its own schema, so a cross-service join is refused by the database itself.

## Status

- [x] Four services, Docker Compose stack with health-gated startup, one-command build
- [x] Users API: registration with BCrypt, unique email, paging, soft delete
- [x] Catalog API: products with separate inventory rows, optimistic locking (409 on stale edits)
- [x] Flyway migrations, RFC 7807 problem responses, request validation
- [x] JWT authentication (HS256): login, per-user ownership checks, service-only endpoints, short-lived service tokens
- [x] Inventory reservations: all-or-nothing, idempotent reserve / commit / release,
      tested with real concurrent races (20 buyers, last 5 units: exactly 5 succeed)
- [x] Integration tests on real MySQL (Testcontainers) + GitHub Actions CI, including an
      end-to-end job that runs the outage demo on every push, and images on GHCR
- [x] Payments: simulated gateway, at most one payment per order (10 concurrent charges -> 1 row),
      and a demo switch that delays charge responses to reproduce timeouts
- [x] Checkout saga: idempotency keys, circuit breakers and timeouts (Resilience4j), and a
      reconciler that settles orders after an outage
- [x] Measured query optimization: 43 -> 4 SQL statements per page, p95 halved (see Benchmarks)

The full design, with the reasoning behind every decision (D10-D27), is in
[docs/designs/resilient-checkout-microservices.md](docs/designs/resilient-checkout-microservices.md).

## Engineering highlights

- **Database-per-service enforced, not just agreed:** per-service MySQL users with
  schema-scoped grants; root is limited to `localhost`. A test proves each user is refused on
  the other schemas.
- **Stock separate from catalog:** `inventory` lives beside `products`, so catalog edits
  (optimistic `@Version`) never conflict with stock changes; `available = on_hand - reserved`,
  guarded by a database CHECK constraint.
- **Honest health checks:** MySQL reports healthy only after its init script has finished,
  because the check logs in as the last user the script creates (`mysqladmin ping` passes even
  on access denied). A bad password fails setup loudly instead of producing a MySQL with no users.
- **Consistent errors:** every error is an RFC 7807 `application/problem+json` body with a
  machine-readable `code`; validation errors list each bad field.
- **Tests that match production:** integration tests start MySQL 8.4 with the same init script
  as Compose; config tests pin ports, jar names and passwords across Compose, Dockerfiles and
  application config so they cannot drift.

## Headline demo: payments goes down mid-checkout

```bash
scripts/build.sh && docker compose up -d --wait
scripts/demo-outage.sh     # with Docker in WSL, run it inside WSL so the VM stays up
```

What it shows (real output from the local stack):

```
== 1. Normal checkout
HTTP 201, status PAID
== 2. Stop payment-service and check out again
HTTP 202, order 4 status PAYMENT_PENDING (stock held: available 6)
message: We're confirming your payment; this order will update on its own.
== 3. Start payment-service; the reconciler settles the order by itself
  after 0s: PAYMENT_PENDING
  ...
  after 43s: PAID
available now: 6 (10 - 2 - 2: both orders' stock committed, none leaked)
PASS: checkout survived the outage and settled to PAID without a second charge.
```

How it works: checkout commits the order as `PAYMENT_PENDING` *before* charging, so an outage
leaves a state that can be finished later. Charges are idempotent per order (one payment row,
enforced by a unique key), calls go through timeouts, retries and circuit breakers
(Resilience4j), and a reconciler runs every 15 s: it asks payment-service what happened to each
pending order and marks it `PAID` (committing the stock) or `PAYMENT_FAILED` (releasing it),
re-sending the charge only if payments never received it.

## Benchmarks: fixing N+1 in the listing endpoints

The two listing endpoints were first written the straightforward way (tag `bench-before`):
each row's images, stock, order lines and history were loaded one row at a time, the classic
N+1 problem. Then two fixes were measured against it, and each endpoint kept its winner
(tag `bench-after`).

| Endpoint (page of 20) | Version | SQL / request | p50 | p95 | Throughput |
|---|---|---|---|---|---|
| `GET /api/products?categoryId` | v1 baseline | 43.0 | 41.0 ms | 66.7 ms | 424 req/s |
| | A: `@BatchSize` + index + batched stock | 5.0 | 23.5 ms | 51.5 ms | 682 req/s |
| | **B: two-step ID paging + index** (kept) | **4.0** | **18.2 ms** | **31.9 ms** | **945 req/s** |
| `GET /api/orders` (own) | v1 baseline | 34.6 | 39.4 ms | 70.5 ms | 435 req/s |
| | **A: `@BatchSize` + index** (kept) | **3.6** | **16.8 ms** | **33.3 ms** | **965 req/s** |
| | B: two-step ID paging + index | 3.6 | 23.1 ms | 44.9 ms | 719 req/s |

**Result:** SQL statements per page 43 -> 4 (products) and 34.6 -> 3.6 (orders); p95 latency
66.7 -> 31.9 ms and 70.5 -> 33.3 ms (both about 52% lower); throughput about 2.2x on both.

Why the winners differ: products have one collection, so paging over ids (straight from the new
`(category_id, id)` index) and then one `JOIN FETCH` is cheapest. Orders have two collections
(lines and history), so two-step needs two fetch queries and the lines join multiplies rows,
while `@BatchSize` loads each collection for the whole page in one light query.
`JOIN FETCH` of a collection is never combined with `Pageable` (Hibernate would page in memory,
HHH90003004), which is why ids are paged first.

**Protocol** (`scripts/bench.sh <variant>`, results in [`benchmarks/results/`](benchmarks/results/)):
fresh database per variant, deterministic seed (`Random(42)`: 1,000 users, 10,000 products x 5
images, 50,000 orders), SQL counted per request by a Hibernate `StatementInspector`
(`X-SQL-Count`, bench profile only), k6 with 20 virtual users: 30 s warm-up, then 3 runs of
2 minutes per endpoint, median reported. Measured on one laptop (4 JVMs + MySQL in WSL), so
compare the rows with each other rather than with other machines.

## Try it in 60 seconds

```bash
scripts/build.sh && docker compose up -d --wait      # build jars + images, start everything

curl -s -X POST localhost:8081/api/users -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse","name":"Ada"}'

TOKEN=$(curl -s -X POST localhost:8081/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')

curl -s -X POST localhost:8082/api/products -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"categoryId":1,"name":"Headphones","price":199.99,"initialStock":25}'

curl -s 'localhost:8082/api/products?categoryId=1&size=5'   # browsing is public
```

| Service | Port | Schema |
|---|---|---|
| user-service | 8081 | `users_db` |
| product-service | 8082 | `products_db` |
| order-service | 8083 | `orders_db` |
| payment-service | 8084 | `payments_db` |

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

## API

| Service | Endpoint | Auth | Notes |
|---|---|---|---|
| user | `POST /api/users` | public | Register; password stored as a BCrypt hash; duplicate email (any case) -> 409 |
| user | `POST /api/auth/login` | public | Returns a 30-minute HS256 JWT; wrong password and unknown email get the same 401 |
| user | `GET /api/users/{id}` | self / internal | 404 ProblemDetail if missing or deleted |
| user | `GET /api/users?page&size` | user | Paged, sorted by id, size <= 100 |
| user | `PUT /api/users/{id}` | self | Change email and name |
| user | `DELETE /api/users/{id}` | self | Soft delete (`active=false`); the email stays taken |
| product | `POST /api/products` | user | Creates the product and its inventory row in one transaction |
| product | `GET /api/products/{id}` | public | `available = on_hand - reserved` |
| product | `GET /api/products?categoryId&page&size` | public | Paged, sorted by id |
| product | `PUT /api/products/{id}` | user | Catalog fields only; send the `version` you read, stale -> 409 |
| product | `POST /api/inventory/reservations` | internal | `{orderId, items}`: all items or none; returns unit prices; a retry replays the result |
| product | `POST /api/inventory/reservations/{orderId}/commit` | internal | Paid: stock leaves (`on_hand` and `reserved` both drop); idempotent |
| product | `DELETE /api/inventory/reservations/{orderId}` | internal | Release: stock returns; before any reserve it leaves a tombstone that blocks a late reserve |

| payment | `POST /api/payments` | internal | `{orderId, amount, paymentToken}`: 201 new, 200 replay of the stored outcome, 409 if the amount differs |
| payment | `GET /api/payments/{id}` | internal | 404 if unknown |
| payment | `GET /api/payments?orderId=` | internal | What happened to an order's charge; never delayed (the reconciler relies on it) |

**Stock reservations** are plain SQL, one conditional `UPDATE ... WHERE on_hand - reserved >= q`
per item, so the database itself refuses to oversell. The order id is the reservation's primary
key, which makes every call idempotent: a retried reserve replays the stored result (with the
prices captured the first time), a changed one gets 409. Items are updated in product-id order,
so two orders sharing products can't deadlock, and lock-timeout losers are retried because every
operation is safe to repeat. Tests race 20 real threads against MySQL for the last 5 units.

**Payments** are charged at most once per order: `UNIQUE(order_id)` makes the database the
referee when a checkout retry and the reconciler charge the same order at the same moment; the
loser re-reads and returns the winner's payment. The gateway is simulated
(`tok_decline` is declined, anything else approved), and `PAYMENT_RESPONSE_DELAY_MS` holds back
every charge response *after* it is committed, to demo a charge that succeeded while its
caller timed out.

**Security:** every service is a stateless OAuth2 resource server that verifies HS256 tokens
with a shared `JWT_SECRET` (at least 32 bytes; a service without it refuses to start).
user-service issues user tokens (`scope=user`); order-service will mint 60-second service tokens
(`scope=internal`) for its calls to products and payments, and a user token can never reach
those internal endpoints (403). 401/403 responses are ProblemDetail bodies too. A shared secret
keeps the setup simple; RS256 with a JWKS endpoint is the production next step (see TODOS.md).

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

## Next steps

Deliberately left out of this version (each is in [TODOS.md](TODOS.md) with the reasoning):

- **Order expiry, payment void and user cancel.** Today an order stays `PAYMENT_PENDING`, with
  its stock held, for as long as payments is down. Expiry plus void would bound that, at the cost
  of new races between void, charge and cancel (cut in design D22).
- **RS256 + JWKS** instead of one shared HS256 secret, so only user-service can sign tokens.
- **Keyset pagination** for the listings (deep `OFFSET` pages still get slower linearly).
- **Transactional outbox + events** instead of the reconciler polling every 15 s.
