// Carga controlada con k6:  k6 run -e BASE_URL=http://localhost:8080 perf/reserve.js
// Objetivo p95: consulta < 200 ms, reserva < 400 ms. Tamano: 20 VUs x 60 s sobre el entorno documentado en el README.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    queries: { executor: 'constant-vus', vus: 15, duration: '60s', exec: 'query' },
    commands: { executor: 'constant-vus', vus: 5, duration: '60s', exec: 'reserve' },
  },
  thresholds: {
    'http_req_duration{scenario:queries}': ['p(95)<200'],
    'http_req_duration{scenario:commands}': ['p(95)<400'],
    // 409 (stock agotado) y 429 (rate limit) son respuestas esperadas del negocio, no fallas
    http_req_failed: ['rate<0.01'],
  },
};
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 429));

export function query() {
  const r = http.get(`${BASE}/api/v1/inventory?sku=SKU-2002&size=10`, { headers: { 'X-Api-Key': __ENV.OPS_KEY || 'dev-ops-key' } });
  check(r, { 'status ok or throttled': (x) => [200, 429].includes(x.status) });
}

export function reserve() {
  const r = http.post(`${BASE}/api/v1/reservations`,
    JSON.stringify({ sku: 'SKU-1001', location: 'BOD-UIO', quantity: 1, destination: 'PERF' }),
    { headers: { 'X-Api-Key': __ENV.FULFILLMENT_KEY || 'dev-fulfillment-key', 'Idempotency-Key': uuidv4(), 'Content-Type': 'application/json' } });
  check(r, { 'created, sold out or throttled': (x) => [201, 409, 429].includes(x.status) });
}
