#!/usr/bin/env bash
# Headline demo: checkout survives a payment-service outage.
#   1. a normal order is PAID (201)
#   2. payment-service is stopped; the next order answers 202 PAYMENT_PENDING, stock held
#   3. payment-service is started again; the reconciler settles the order to PAID on its own
# Needs the stack running (scripts/build.sh && docker compose up -d --wait).
# From Git Bash with Docker in WSL:  DOCKER="wsl.exe --cd $(pwd -W) docker" scripts/demo-outage.sh
set -euo pipefail
cd "$(dirname "$0")/.."

DOCKER=${DOCKER:-docker}
USERS=${USERS_URL:-http://localhost:8081}
PRODUCTS=${PRODUCTS_URL:-http://localhost:8082}
ORDERS=${ORDERS_URL:-http://localhost:8083}
TIMEOUT_S=${SETTLE_TIMEOUT_S:-120}

json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)" 2>/dev/null || python -c "import sys, json; print(json.load(sys.stdin)$1)"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "== $*"; }

step "Sign up, log in, and stock a product"
EMAIL="demo-$(date +%s)-$RANDOM@example.com"
curl -sf -X POST "$USERS/api/users" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse\",\"name\":\"Demo\"}" > /dev/null
TOKEN=$(curl -sf -X POST "$USERS/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse\"}" | json "['accessToken']")
AUTH="Authorization: Bearer $TOKEN"
PRODUCT=$(curl -sf -X POST "$PRODUCTS/api/products" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"categoryId":1,"name":"Demo headphones","price":50.00,"initialStock":10}' | json "['id']")
available() { curl -sf "$PRODUCTS/api/products/$PRODUCT" | json "['available']"; }
echo "product $PRODUCT, available $(available)"

order() { # $1 = idempotency key; prints "<http status> <order json>"
  curl -s -w '\n%{http_code}' -X POST "$ORDERS/api/orders" -H "$AUTH" -H "Idempotency-Key: $1" \
    -H 'Content-Type: application/json' \
    -d "{\"items\":[{\"productId\":$PRODUCT,\"quantity\":2}],\"paymentToken\":\"tok_visa\"}"
}

step "1. Normal checkout"
RESP=$(order "demo-ok-$RANDOM"); CODE=$(tail -n1 <<<"$RESP"); BODY=$(head -n -1 <<<"$RESP")
echo "HTTP $CODE, status $(json "['status']" <<<"$BODY")"
[ "$CODE" = 201 ] || fail "expected 201, got $CODE: $BODY"

step "2. Stop payment-service and check out again"
$DOCKER compose stop payment-service > /dev/null
RESP=$(order "demo-outage-$RANDOM"); CODE=$(tail -n1 <<<"$RESP"); BODY=$(head -n -1 <<<"$RESP")
ORDER=$(json "['id']" <<<"$BODY")
echo "HTTP $CODE, order $ORDER status $(json "['status']" <<<"$BODY") (stock held: available $(available))"
echo "message: $(json "['message']" <<<"$BODY")"
[ "$CODE" = 202 ] || fail "expected 202 while payments is down, got $CODE: $BODY"

step "3. Start payment-service; the reconciler settles the order by itself"
$DOCKER compose start payment-service > /dev/null
START=$(date +%s)
while :; do
  STATUS=$(curl -sf "$ORDERS/api/orders/$ORDER" -H "$AUTH" | json "['status']" || echo "?")
  ELAPSED=$(( $(date +%s) - START ))
  echo "  after ${ELAPSED}s: $STATUS"
  [ "$STATUS" = PAID ] && break
  [ "$ELAPSED" -ge "$TIMEOUT_S" ] && fail "order $ORDER not PAID after ${TIMEOUT_S}s"
  sleep 5
done

step "Result"
curl -sf "$ORDERS/api/orders/$ORDER" -H "$AUTH" | json "['history']" | tr '}' '\n' | grep -o "'to': '[A-Z_]*'" | sed "s/'to': /  -> /"
echo "available now: $(available) (10 - 2 - 2: both orders' stock committed, none leaked)"
[ "$(available)" = 6 ] || fail "expected 6 available"
echo; echo "PASS: checkout survived the outage and settled to PAID without a second charge."
