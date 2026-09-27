# TODOS

## Payment expiry, void and user cancel
**What:** A 10-minute expiry for PAYMENT_PENDING orders, a payment void endpoint with a VOIDED tombstone, and user cancel.
**Why:** Today a pending order holds its stock for as long as payment-service is down.
**Pros:** Bounded stock hold time; a complete saga story.
**Cons:** It adds the VOIDED races that were cut in eng review D22 (~8-10h).
**Context:** Cut in design review (D22). Full rules (void on none/APPROVED/DECLINED, VOIDED -> CANCELLED) are in the original design notes (D12-D14).
**Depends on / blocked by:** v1 checkout + reconciler.

## RS256 + JWKS instead of a shared HS256 secret
**What:** user-service signs with RS256 and publishes a JWKS; the other services validate via jwk-set-uri.
**Why:** With a shared secret, any service can mint tokens.
**Pros:** Standard production setup; key rotation.
**Cons:** More config; key management.
**Context:** D23 chose HS256 for simplicity.
**Depends on / blocked by:** JWT v1.

## Keyset pagination for listing endpoints
**What:** Replace offset paging with seek pagination on (id) and (created_at, id).
**Why:** Deep offsets degrade linearly.
**Pros:** Another measured optimization.
**Cons:** API shape changes (cursor instead of page).
**Context:** Excluded from the benchmark claim in the design doc.
**Depends on / blocked by:** Benchmark harness.

## Transactional outbox / events
**What:** Emit order/payment events via an outbox table and a broker.
**Why:** Async decoupling; replaces polling the reconciler.
**Pros:** A common interview topic.
**Cons:** Adds a broker and new failure modes.
**Context:** Approach "event-driven saga" was rejected during design (D3) for time.
**Depends on / blocked by:** v1 complete.
