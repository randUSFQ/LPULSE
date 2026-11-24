#!/usr/bin/env bash
# SAT automatizado (docs/sat.md): recibir/consultar stock -> reservar (FEFO) -> idempotencia -> alerta -> dashboard.
# Uso: BASE_URL=http://localhost:8080 OPS_KEY=... FULFILLMENT_KEY=... bash scripts/sat.sh
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
OPS_KEY="${OPS_KEY:-dev-ops-key}"
FULFILLMENT_KEY="${FULFILLMENT_KEY:-dev-fulfillment-key}"
KEY="sat-$(date +%s)-$RANDOM"
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1  (esperado: $2, observado: $3)"; exit 1; }
expect() { [ "$2" = "$3" ] && pass "$1" || fail "$1" "$2" "$3"; }

# SAT-1 consulta de stock (US-01)
code=$(curl -s -o /tmp/sat_inv.json -w '%{http_code}' -H "X-Api-Key: $OPS_KEY" "$BASE_URL/api/v1/inventory?sku=SKU-1001&sort=expiresOn")
expect "SAT-1 GET inventory => 200" 200 "$code"

# SAT-2 sin credenciales => 401 ; sin scope => 403
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/inventory")
expect "SAT-2a sin X-Api-Key => 401" 401 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Api-Key: $OPS_KEY" -H "Idempotency-Key: $KEY-x" \
       -H 'Content-Type: application/json' -d '{"sku":"SKU-1001","location":"BOD-UIO","quantity":1,"destination":"X"}' \
       "$BASE_URL/api/v1/reservations")
expect "SAT-2b ops-console sin scope reserve => 403" 403 "$code"

# SAT-3 reserva FEFO (US-02)
body='{"sku":"SKU-1001","location":"BOD-UIO","quantity":35,"destination":"PEDIDO-SAT"}'
code=$(curl -s -o /tmp/sat_res.json -w '%{http_code}' -X POST -H "X-Api-Key: $FULFILLMENT_KEY" \
       -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$body" "$BASE_URL/api/v1/reservations")
expect "SAT-3 POST reservations => 201" 201 "$code"

# SAT-4 repetir la misma peticion => 200 y misma reserva (US-02 idempotencia)
code=$(curl -s -o /tmp/sat_res2.json -w '%{http_code}' -X POST -H "X-Api-Key: $FULFILLMENT_KEY" \
       -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$body" "$BASE_URL/api/v1/reservations")
expect "SAT-4a replay => 200" 200 "$code"
[ "$(jq -r .reservationId /tmp/sat_res.json)" = "$(jq -r .reservationId /tmp/sat_res2.json)" ] \
  && pass "SAT-4b misma reservationId" || fail "SAT-4b misma reservationId" iguales distintas

# SAT-5 stock insuficiente => 409 con problem+json
code=$(curl -s -o /tmp/sat_409.json -w '%{http_code}' -X POST -H "X-Api-Key: $FULFILLMENT_KEY" \
       -H "Idempotency-Key: $KEY-big" -H 'Content-Type: application/json' \
       -d '{"sku":"SKU-1001","location":"BOD-UIO","quantity":99999,"destination":"X"}' "$BASE_URL/api/v1/reservations")
expect "SAT-5 stock insuficiente => 409" 409 "$code"
expect "SAT-5b code INSUFFICIENT_STOCK" INSUFFICIENT_STOCK "$(jq -r .code /tmp/sat_409.json)"

# SAT-6 dashboard actualizado por el consumidor (consistencia eventual: reintentar hasta 15 s)
ok=0
for i in $(seq 1 15); do
  n=$(curl -s -H "X-Api-Key: $OPS_KEY" "$BASE_URL/api/v1/dashboard/inventory" | jq '[.items[] | select(.sku=="SKU-1001")] | length')
  [ "$n" = "1" ] && ok=1 && break
  sleep 1
done
[ "$ok" = 1 ] && pass "SAT-6 dashboard refleja SKU-1001 con lastUpdatedAt" || fail "SAT-6 dashboard" "fila SKU-1001" "ausente"

# SAT-7 reposicion (US-03): SKU-2002 tiene 25 u. y punto de reorden 20; reservar 10 lo deja en 15 (< 20).
# 201 en la primera corrida; 409 si corridas previas ya agotaron el stock semilla (la sugerencia sigue abierta).
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Api-Key: $FULFILLMENT_KEY" -H "Idempotency-Key: $KEY-s2" \
       -H 'Content-Type: application/json' -d '{"sku":"SKU-2002","location":"BOD-UIO","quantity":10,"destination":"PEDIDO-SAT"}' \
       "$BASE_URL/api/v1/reservations")
case "$code" in 201|409) pass "SAT-7a reserva SKU-2002 => $code";; *) fail "SAT-7a" "201|409" "$code";; esac
sid=""
for i in $(seq 1 15); do
  sid=$(curl -s -H "X-Api-Key: $OPS_KEY" "$BASE_URL/api/v1/replenishment/suggestions?status=OPEN&sku=SKU-2002" | jq -r '.[0].id // empty')
  [ -n "$sid" ] && break
  sleep 1
done
[ -n "$sid" ] && pass "SAT-7b sugerencia OPEN para SKU-2002" || fail "SAT-7b sugerencia" "OPEN" "ninguna"
n=$(curl -s -H "X-Api-Key: $OPS_KEY" "$BASE_URL/api/v1/replenishment/suggestions?status=OPEN&sku=SKU-2002" | jq 'length')
expect "SAT-7c una sola sugerencia abierta (sin duplicados)" 1 "$n"

# SAT-8 aprobar: 200 la primera vez, 409 la segunda (transicion invalida)
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Api-Key: $OPS_KEY" "$BASE_URL/api/v1/replenishment/suggestions/$sid/approve")
expect "SAT-8a approve => 200" 200 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Api-Key: $OPS_KEY" "$BASE_URL/api/v1/replenishment/suggestions/$sid/approve")
expect "SAT-8b approve repetido => 409" 409 "$code"
echo "SAT completado: $(date -u +%FT%TZ)"
