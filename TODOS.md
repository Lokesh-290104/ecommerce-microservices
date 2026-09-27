# TODOS

## Checkout

### Payment expiry, void and user cancel

**What:** A 10-minute expiry for PAYMENT_PENDING orders, a payment void endpoint with a VOIDED tombstone, and user cancel.

**Why:** Today a pending order holds its stock for as long as payment-service is down.

**Context:** Cut in design review (D22). Full rules (void on none/APPROVED/DECLINED, VOIDED -> CANCELLED) are in the original design notes (D12-D14). Pros: bounded stock hold time; a complete saga story. Cons: it adds the VOIDED races that were cut in D22 (~8-10h).

**Effort:** L
**Priority:** P2
**Depends on:** v1 checkout + reconciler.

## Security

### RS256 + JWKS instead of a shared HS256 secret

**What:** user-service signs with RS256 and publishes a JWKS; the other services validate via jwk-set-uri.

**Why:** With a shared secret, any service can mint tokens.

**Context:** D23 chose HS256 for simplicity. Pros: standard production setup; key rotation. Cons: more config; key management.

**Effort:** M
**Priority:** P3
**Depends on:** JWT v1.

## Performance

### Keyset pagination for listing endpoints

**What:** Replace offset paging with seek pagination on (id) and (created_at, id).

**Why:** Deep offsets degrade linearly.

**Context:** Excluded from the benchmark claim in the design doc. Pros: another measured optimization. Cons: API shape changes (cursor instead of page).

**Effort:** M
**Priority:** P3
**Depends on:** Benchmark harness.

## Architecture

### Transactional outbox / events

**What:** Emit order/payment events via an outbox table and a broker.

**Why:** Async decoupling; replaces polling the reconciler.

**Context:** Approach "event-driven saga" was rejected during design (D3) for time. Pros: a widely used production pattern. Cons: adds a broker and new failure modes.

**Effort:** XL
**Priority:** P4
**Depends on:** v1 complete.

## Completed
