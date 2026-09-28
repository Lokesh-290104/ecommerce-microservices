#!/usr/bin/env bash
# Benchmarks one variant of the listing endpoints (step 8, design D21/D26), from a clean slate:
#   scripts/bench.sh <variant>      e.g. baseline | batchsize | twostep
# Build the images first (scripts/build.sh -DskipTests). Run where `docker` works (inside WSL
# if Docker lives there, which also keeps the WSL VM awake for the whole run).
# Protocol: fresh volume, deterministic seed (Random(42)), SQL counts from X-SQL-Count on 20
# sample requests, 30 s warm-up, then RUNS x DURATION with VUS virtual users per endpoint.
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT=${1:?usage: scripts/bench.sh <variant>}
RUNS=${RUNS:-3}
DURATION=${DURATION:-2m}
VUS=${VUS:-20}
OUT=benchmarks/results/$VARIANT
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.bench.yml"
K6="docker run --rm --network shop_default -v $PWD/benchmarks:/bench grafana/k6:latest"

echo "== [$VARIANT] fresh stack + seed"
$COMPOSE down -v --remove-orphans > /dev/null 2>&1 || true
$COMPOSE up -d --wait --wait-timeout 300 > /dev/null
for svc in user-service product-service order-service; do
    if ! out=$($COMPOSE run --rm -e SPRING_PROFILES_ACTIVE=seed "$svc" 2>&1); then
        echo "$out" | tail -40; echo "seeding $svc failed" >&2; exit 1
    fi
    echo "$out" | grep -oE '(Seeded|seeding skipped).*' || true
done
mkdir -p "$OUT"
git rev-parse --short HEAD > "$OUT/commit.txt"

echo "== [$VARIANT] SQL statements per request"
TOKEN_SCRIPT='import hmac,hashlib,base64,json,time,sys
b=lambda d: base64.urlsafe_b64encode(json.dumps(d,separators=(",",":")).encode()).rstrip(b"=").decode()
n=int(time.time()); h=b({"alg":"HS256"}); p=b({"iss":"shop-user-service","sub":sys.argv[1],"scope":"user","iat":n,"exp":n+600})
print(h+"."+p+"."+base64.urlsafe_b64encode(hmac.new(b"local-dev-jwt-secret-change-me-0123456789",(h+"."+p).encode(),hashlib.sha256).digest()).rstrip(b"=").decode())'
sql_count() { curl -s -o /dev/null -D - "$@" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-sql-count"{print $2}'; }
{
    for i in $(seq 1 20); do
        echo "products $(sql_count "http://localhost:8082/api/products?categoryId=$(( (i % 50) + 1 ))&page=$(( i % 10 ))&size=20")"
        echo "orders $(sql_count -H "Authorization: Bearer $(python3 -c "$TOKEN_SCRIPT" $(( i * 37 % 1000 + 1 )))" "http://localhost:8083/api/orders?page=$(( i % 3 ))&size=20")"
    done
} > "$OUT/sql-counts.txt"
awk '{s[$1]+=$2; n[$1]++} END {for (e in s) printf "  %s: %.1f statements/request\n", e, s[e]/n[e]}' "$OUT/sql-counts.txt"

for ep in products orders; do
    echo "== [$VARIANT] $ep: 30s warm-up"
    $K6 run -q -e ENDPOINT=$ep -e DURATION=30s -e VUS=$VUS /bench/k6/listing.js > /dev/null
    for run in $(seq 1 "$RUNS"); do
        echo "== [$VARIANT] $ep: run $run/$RUNS ($VUS VUs x $DURATION)"
        $K6 run -q -e ENDPOINT=$ep -e DURATION=$DURATION -e VUS=$VUS \
            --summary-export "/bench/results/$VARIANT/$ep-run$run.json" /bench/k6/listing.js > /dev/null
    done
done
echo "== [$VARIANT] done: $OUT"
