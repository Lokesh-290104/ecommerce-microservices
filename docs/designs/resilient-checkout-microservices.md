# Design: E-Commerce Microservices Platform (Resilient Checkout)

Status: approved. Decision IDs (D10-D27) are referenced throughout.

## Problem Statement

An e-commerce backend split into four services (users, products, orders, payments) on Java,
Spring Boot, Hibernate and MySQL, run with Docker. A checkout spans three of those services,
so the design has to answer what happens when one of them is slow or down mid-checkout:
orders must not be lost or double-charged, and reserved stock must not leak. The platform also
exposes paginated listing endpoints whose query cost is measured and optimized.

## Goals

Two behaviours the system must demonstrate end to end:

1. **Payments outage mid-checkout.** `docker compose stop payment-service`, then place an order:
   202 + `PAYMENT_PENDING`, not a 500, and the stock stays reserved. Start payments again and
   the order becomes `PAID` on its own within about 60s. It runs as an E2E job in CI on every
   push (D19). The ambiguous variant: payments *charges* but every POST answer comes after
   the 2s timeout (D16). Checkout gives up at PENDING, the reconciler's lookup finds the
   charge, and there is exactly one payment. A WireMock test covers it (must-have); the live
   script is a should-have.
2. **Measured query optimization.** A committed k6 benchmark plus per-request SQL counts compare
   the baseline, `@BatchSize` and two-step ID paging (D21). Results lead with the measured
   facts ("X -> Y SQL statements per page, p95 A ms -> B ms"); any percentage is secondary and
   is whatever was measured (D26).

## Development Environment

- JDK 21 at `JAVA_HOME`; always build with `./mvnw` (it honors JAVA_HOME). No global Maven.
  Docker + Compose v2 run inside WSL Ubuntu on Windows; k6 runs from the `grafana/k6` image.
- **Tests (D11-B):** the WSL Docker daemon also listens on `tcp://127.0.0.1:2375` (localhost
  only, never 0.0.0.0), and Windows sets `DOCKER_HOST=tcp://localhost:2375` so the Windows JVM
  runs Testcontainers. Setup notes:
  - Use a systemd drop-in (`ExecStart=... -H fd:// -H tcp://127.0.0.1:2375`), not `hosts` in
    `daemon.json`, which conflicts with `-H fd://`.
  - Keep WSL alive during test runs (an open WSL shell, or `vmIdleTimeout` in `.wslconfig`).
  - If mapped ports flake on first connect, switch WSL to mirrored networking.
  - The README warns that this socket is unauthenticated and root-equivalent for local
    processes.

## Premises

1. **Stack:** Java 21 + **Spring Boot 4.1.x** (3.5 reached OSS end of life on 2026-06-30).
   Resilience4j via `resilience4j-spring-boot4` 2.4.0+, version pinned (early BOM gap).
2. **Shape (D10):** one repo, Maven multi-module. The parent POM carries versions only; the 4
   service modules are fully independent. `GlobalExceptionHandler`/`ErrorCode` are
   deliberately duplicated per service (no shared jar, so no coupled deploys).
3. **Data:** one MySQL 8 container, 4 databases, one per service; no cross-service joins/FKs.
4. **Comms:** synchronous REST via `RestClient` + Resilience4j; no Eureka, gateway or broker.
5. **Auth (D23):** minimal JWT with Spring Security (see Security).
6. **Measured claims only:** benchmark numbers come from committed runs.

## Approaches Considered

- **A: Four clean services.** Rejected: pending orders never recover, stock leaks, and double
  submit double-charges.
- **C: Chaos-first (Toxiproxy).** Rejected: most of the effort goes into infrastructure rather
  than the services themselves.
- **Deferred (D22):** user cancel, the 10-minute expiry and payment void/tombstones. They
  introduce most of the race conditions for little core value. Listed as a next step.

## Chosen Approach: B, Resilient Checkout

### Architecture

```
                      client (curl / Postman / k6)  --JWT-->
          |                |                   |                  |
          v                v                   v                  v
   user-service     product-service      order-service      payment-service
      :8081             :8082               :8083               :8084
   (issues JWT)           ^                 |    |    |            ^
        ^                 +-- RestClient ---+    |    +-RestClient-+
        +---------------------- RestClient ------+   (service JWT, scope=internal)
        v                 v                   v                    v
    users_db         products_db          orders_db           payments_db
    \_________________ one MySQL 8 container, 4 schemas ________________/

    order-service: CheckoutService (no tx) -> OrderStateService (@Transactional, short)
                   Reconciler (@Scheduled every 15s, no tx) -> OrderStateService
```

### API inventory (19 endpoints)

| # | Service | Endpoint | Auth | Notes |
|---|---|---|---|---|
| 1 | user | `POST /api/users` | public | register; BCrypt `password_hash`; duplicate email -> 409 |
| 2 | user | `POST /api/auth/login` | public | returns an HS256 JWT (30 min), sub=userId |
| 3 | user | `GET /api/users/{id}` | user (self) / internal | 404 via `@RestControllerAdvice` |
| 4 | user | `GET /api/users?page&size` | user | `Pageable` |
| 5 | user | `PUT /api/users/{id}` | self | |
| 6 | user | `DELETE /api/users/{id}` | self | soft delete (`active=false`) |
| 7 | product | `POST /api/products` | user | creates product + inventory row |
| 8 | product | `GET /api/products/{id}` | public | available = on_hand - reserved |
| 9 | product | `GET /api/products?categoryId&page&size` | public | **benchmark #1**; sorted by `id` |
| 10 | product | `PUT /api/products/{id}` | user | catalog fields only; `@Version` -> 409 |
| 11 | product | `POST /api/inventory/reservations` | internal | `{orderId, items}`; returns unit prices; atomic; idempotent |
| 12 | product | `POST /api/inventory/reservations/{orderId}/commit` | internal | on PAID; idempotent |
| 13 | product | `DELETE /api/inventory/reservations/{orderId}` | internal | release; idempotent; RELEASED tombstone |
| 14 | order | `POST /api/orders` | user | `Idempotency-Key` required; userId comes from the JWT |
| 15 | order | `GET /api/orders/{id}` | owner | items + status history; another user's order -> 403 |
| 16 | order | `GET /api/orders?page&size` | owner | **benchmark #2**; the caller's orders, sorted by `created_at desc` |
| 17 | payment | `POST /api/payments` | internal | `{orderId, amount, paymentToken}`; idempotent per orderId |
| 18 | payment | `GET /api/payments/{id}` | internal | |
| 19 | payment | `GET /api/payments?orderId` | internal | APPROVED / DECLINED, or 404 |

Simulated gateway: `paymentToken = tok_decline` means DECLINED; anything else means APPROVED.
Demo env `PAYMENT_RESPONSE_DELAY_MS` delays **every POST /api/payments response** (commit first,
then sleep), including idempotent replays. GET is never delayed (D16).

The benchmark uses a bench-profile token issued for the seeded users; the order listing takes
its userId from the token, and k6 rotates tokens across users 1..1000.

### Security (D23)

- user-service issues HS256 JWTs. All 4 services are Spring Security OAuth2 resource servers
  validating with the same secret from env `JWT_SECRET`. The README notes the tradeoff: a shared
  secret is simple, while RS256 + JWKS is the next step.
- Service-to-service: order-service mints a short-lived (60s) service JWT with scope
  `internal` for calls to product-service and payment-service. Internal endpoints require
  `SCOPE_internal`. User-facing order reads check `order.userId == jwt.sub`, else 403.
- Stateless sessions, CSRF disabled (no cookies), 401/403 as ProblemDetail.

### Inventory model (product-service)

- `inventory(product_id PK, on_hand, reserved)`, separate from `products` so catalog `@Version`
  edits never conflict with stock.
- `reservations(order_id PK, status RESERVED|COMMITTED|RELEASED)` + `reservation_items`.
- **Reserve** (one transaction): INSERT the reservation row first; a duplicate PK rolls back,
  re-reads and returns the existing result (RESERVED: same prices; RELEASED: 409
  RESERVATION_RELEASED; COMMITTED: 409). Then, per item sorted by `product_id`:
  `UPDATE inventory SET reserved = reserved + :q WHERE product_id = :id AND on_hand - reserved >= :q`.
  If 0 rows are updated, roll back everything: 409 INSUFFICIENT_STOCK. Unknown product: 422.
  Test: 20 threads race for the last 5 units and exactly 5 succeed.
- **Release**: RESERVED -> RELEASED (give the stock back). No row -> INSERT a RELEASED tombstone
  (blocks a late reserve). RELEASED -> 200 no-op. COMMITTED -> 409.
- **Commit**: RESERVED -> COMMITTED (on_hand -= q, reserved -= q). COMMITTED -> 200 no-op.
  RELEASED or missing -> 409.

### Payment uniqueness (D12)

`UNIQUE(order_id)` on `payments`. A losing concurrent INSERT (checkout retry vs reconciler
re-POST) catches `DataIntegrityViolationException` outside the failed transaction, re-reads and
returns the existing record. Test: 10 concurrent POSTs for one orderId produce 1 row.

### Checkout flow (order-service)

```
POST /api/orders  (Authorization: Bearer <user JWT>, Idempotency-Key: K)
  +-- header missing -> 400
  +-- (user_id, K) exists? same request hash -> replay; different -> 422 IDEMPOTENCY_KEY_REUSED
  +-- GET user   inactive/404 -> 422 USER_NOT_FOUND;  unavailable -> 503   (nothing persisted)
  +-- tx1 INSERT order CREATED (unique (user_id, idempotency_key), request_hash, payment_token)
  |      unique violation -> re-read winner, compare hash: same -> replay; different -> 422
  +-- POST reservation   (no transaction open)
  |      200 -> tx2 prices + total, PAYMENT_PENDING, pending_since=now   (committed before paying)
  |      409/422 -> tx2 CANCELLED(OUT_OF_STOCK|INVALID_ITEMS) -> 409/422
  |      unavailable -> tx2 CANCELLED(INVENTORY_UNAVAILABLE) + RELEASE_PENDING -> 503
  +-- POST payment       (no transaction open)
         APPROVED -> tx3 PAID + COMMIT_PENDING, try commit            -> 201
         DECLINED -> tx3 PAYMENT_FAILED + RELEASE_PENDING, try release -> 402
         unavailable -> stay PAYMENT_PENDING                           -> 202
  +-- any tx loses the @Version race -> re-read, return current state (mapping below)
```

- **Transaction boundaries (D18):** `CheckoutService` and `Reconciler` are not transactional.
  Every DB write is a short `@Transactional` method on a separate `OrderStateService` bean,
  which avoids holding connections across HTTP calls and the self-invocation proxy trap. A test
  asserts no transaction is active inside RestClient calls.
- **"Unavailable"** = connection refused, unknown host, connect/read timeout, 5xx or an open
  breaker. 4xx is a normal answer, never a failure (see Resilience).
- **Replay contract:** `response_status` is stored from the first attempt. A replay returns it
  with the current body. If it is still null (first request in flight, or a crash), derive the
  code from the current state (D15). Mapping: PAID 201, CREATED/PAYMENT_PENDING 202,
  PAYMENT_FAILED 402, CANCELLED 409 (with reason). Error bodies say "retry with a new
  Idempotency-Key".

### Reconciler (@Scheduled every 15s)

| Order state | Condition | Action |
|---|---|---|
| CREATED | older than 60s | CANCELLED(TIMED_OUT) + RELEASE_PENDING, then try release (the tombstone blocks a late reserve) |
| PAYMENT_PENDING | `pending_since` < now - 10s (D27) | `GET /api/payments?orderId`: APPROVED -> PAID + COMMIT_PENDING; DECLINED -> PAYMENT_FAILED + RELEASE_PENDING; 404 -> re-POST (idempotent; token stored on the order) |
| any | inventory_action RELEASE_PENDING / COMMIT_PENDING | retry release/commit. I/O or 5xx: retry next tick. **4xx: inventory_action = FAILED + ERROR log with orderId, stop** (D24). Success: DONE. |

A PAYMENT_PENDING order waits as long as payments is down, with stock held. That is a documented
limitation; the README next step is expiry + void (D22). Every transition uses `@Version`; a
reconciler update that loses the race is skipped until the next tick. Indexes
`orders(status, pending_since)` and `orders(inventory_action)` are in V1 (D20).

### Order state machine

```
CREATED --> PAYMENT_PENDING --> PAID
   |              '-----------> PAYMENT_FAILED
   '--> CANCELLED (OUT_OF_STOCK | INVALID_ITEMS | INVENTORY_UNAVAILABLE | TIMED_OUT)
   '--> PAID | PAYMENT_FAILED  (first payment call answered directly)
```

`OrderStatus.canTransitionTo` holds all the rules. Every transition writes `order_status_history`.
`inventory_action` is NONE | RELEASE_PENDING | COMMIT_PENDING | DONE | FAILED.

### Resilience (per downstream call)

| Call | Timeout (HTTP client) | Inline retry | Circuit breaker |
|---|---|---|---|
| user GET | connect 500ms / read 1s | 1 | `userCB` |
| reservation POST | 500ms / 2s | none | `inventoryCB` |
| commit / release | 500ms / 2s | none (reconciler) | `inventoryCB` |
| payment POST | 500ms / 2s | 2, 200ms backoff | `paymentCB` |
| payment GET | 500ms / 2s | none | `paymentCB` (shared with the reconciler) |

Required configuration (necessary for the "unavailable" contract):
- Timeouts are the **RestClient's HTTP request factory connect/read timeouts**. Resilience4j
  `TimeLimiter` is async-only and is not used.
- `recordExceptions`/`retryExceptions` = I/O exceptions (`ResourceAccessException`) +
  `HttpServerErrorException`. `ignoreExceptions` = `HttpClientErrorException` (4xx). Never retry
  `CallNotPermittedException`.
- Aspect order: Retry wraps CircuitBreaker (default), so one checkout during an outage records
  up to 3 failures. Breaker: count window 10, minimum 5 calls, 50%, 20s open, 3 half-open.
  The demo bound is PAID within ~60s of payments being healthy.

### Query optimization plan

**Framing (D26):** v1 of the two listing endpoints is written the straightforward way (lazy
associations mapped in a loop, no secondary indexes), committed and tagged `bench-before`,
measured, then fixed. The README says the baseline was a first-pass implementation. Results
are reported as SQL counts and ms values.

Fixes (their own commits):
- Indexes `products(category_id, id)` and `orders(user_id, created_at)`.
- Collections: `@BatchSize` vs two-step ID paging (page the IDs, then fetch those rows with
  images/items). **Both are measured; the code keeps the winner** (D21). No JOIN FETCH +
  `Pageable` on collections (HHH90003004).
- `@EntityGraph` for `product.category`. Expect a near-zero gain: one categoryId per page means
  the lazy to-one costs 1 query, not 20. The real N+1 is images/items, and the README says so.
- DTO projection where useful. Offset paging stays (keyset is a next step).

Measurement:
- Per-service `SeedRunner` (profile `seed`, `Random(42)`), with JDBC batching
  (`rewriteBatchedStatements=true`, `hibernate.jdbc.batch_size=500`): users 1..1000 (known
  password), 50 categories, products 1..10000 x 5 images with `price = f(productId)`, 50k
  orders (1-5 items) **only in terminal states** (PAID ~80%, CANCELLED, PAYMENT_FAILED) with
  inventory_action DONE (D17). Reset per variant: `down -v`, `up`, seed.
- k6 (Docker image): products `categoryId` 1..50, `page` 0..9, `size=20`; orders use tokens for
  users 1..1000, `page` 0..2, `size=20`. 30s warm-up, then 20 VUs x 2 min x 3 runs per variant;
  report the median p50/p95. Statistics and SQL logging are off.
- SQL count: separate runs with a `StatementInspector` -> `X-SQL-Count` header (profile `bench`).
- Results go in `benchmarks/results/{baseline,batchsize,twostep}/` plus a README table.

### Build and run

- `mvnw package` (Windows, JDK 21, or CI) produces jars. Each service has a single-stage
  Dockerfile (`eclipse-temurin:21-jre`, `COPY target/*.jar`) (D25). `scripts/build.sh` runs
  package, then `docker compose build` in WSL.
- Global `@RestControllerAdvice` -> RFC 7807 `ProblemDetail`; Bean Validation on request DTOs;
  Flyway per service (`ddl-auto=validate`); Actuator health + compose `service_healthy`.


## Open Questions

None blocking. Resolved in review: Q1 -> D11 (Docker exposed to Windows), Q2 -> D10 (no shared
module), Q3 -> D21 (measure both).

## Success Criteria

Must-have:
- `docker compose up` in WSL: MySQL + 4 services healthy.
- 19 endpoints live, each with at least one test.
- JWT login; protected endpoints return 401 without a token and 403 for another user's order;
  internal endpoints reject user tokens.
- Outage demo: payments stopped -> 202 PAYMENT_PENDING, stock reserved; payments started -> PAID
  within ~60s; one payment row; reservation COMMITTED.
- Ambiguous-timeout WireMock test: all POSTs time out -> PENDING -> the reconciler finds
  APPROVED -> PAID, one payment.
- Double submit (sequential, concurrent, in flight) with one key: one order, one payment;
  different body -> 422.
- Decline -> 402, stock restored, including a release retried after a product-service blip;
  a 4xx on release -> FAILED, not an infinite loop.
- Reservation concurrency: 20 threads / 5 units -> exactly 5. Payment concurrency: 10 POSTs -> 1 row.
- No transaction active during RestClient calls.
- Benchmark README table (baseline / @BatchSize / two-step, p95 + SQL count) from committed
  results.
- GitHub Actions: `mvn verify` on push/PR.

Should-have (cut in this order if needed):
1. Images pushed to GHCR on `main`.
2. `scripts/demo-ambiguous.sh` (the live version of the WireMock test).
3. CI E2E job running `demo-outage.sh` with assertions (D19).
4. The second D21 variant (keep only two-step; report baseline vs after).

## Distribution

A public GitHub repo. GitHub Actions: build + test on push/PR, E2E job, GHCR push on `main`
(should-have). No cloud deploy; the README gives a one-command local run.

## Implementation Plan

0. Create the GitHub repo and remote.
1. Parent POM (Boot 4.1), 4 modules, Maven Wrapper, Dockerfiles, compose + MySQL init
   (4 DBs), `scripts/build.sh`. 4 green health checks.
2. Expose the WSL Docker daemon on 127.0.0.1:2375 (systemd drop-in) and get one
   Testcontainers MySQL test green from Windows. First CI workflow (`mvn verify`).
3. user-service + product-service catalog CRUD, Flyway, ProblemDetail, validation, tests.
4. JWT: login, BCrypt, resource-server config on all 4 services, service tokens, owner
   checks, token test helper.
5. Inventory reserve/commit/release, tombstones, concurrency test.
6. payment-service: gateway, UNIQUE(order_id) idempotency, delay flag, concurrency test.
7. order-service: CheckoutService + OrderStateService, idempotency + replay, Resilience4j
   config (exception classification), state machine + history, reconciler (D24, D27),
   WireMock tests for every row of the flow and reconciler tables.
8. Seed runners, `bench-before`, 3 k6 variants + SQL counts, fixes, `bench-after`,
   README table.
9. CI E2E job, README (diagram, demos, the D11 security note, next steps: expiry/void/
   cancel, RS256/JWKS, keyset paging, outbox/events).
