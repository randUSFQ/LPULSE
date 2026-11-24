# Guion SAT (aceptación del sistema)

Precondición: `docker compose up -d --build` con datos semilla (V2). Automatizado en `scripts/sat.sh`.
Columnas *Observado* y *Responsable* las completa el equipo en cada ejecución (adjuntar salida del script).

| ID | Historia | Paso | Resultado esperado | Observado | Responsable |
|---|---|---|---|---|---|
| SAT-1 | US-01 | `GET /api/v1/inventory?sku=SKU-1001` | 200; `physical`, `reserved` y `available` por lote | | |
| SAT-2 | NFR seguridad | Sin `X-Api-Key` / con scope insuficiente | 401 / 403 `problem+json` | | |
| SAT-3 | US-02 | `POST /reservations` 35 u. de SKU-1001 | 201; lote `L-B` (vence 2026-12-01) se consume antes que `L-A`; `L-OLD` (vencido) nunca | | |
| SAT-4 | US-02 | Repetir con la misma `Idempotency-Key` | 200, `Idempotent-Replay: true`, misma `reservationId`, `reserved` sin cambio | | |
| SAT-5 | US-02 | Reservar 99 999 u. | 409 `INSUFFICIENT_STOCK`; stock intacto | | |
| SAT-6 | US-04 | `GET /dashboard/inventory` (≤ 15 s) | Fila SKU-1001 con `lastUpdatedAt`, `processingDelayMs`, `stale=false` | | |
| SAT-7 | US-03 | Reservar 10 u. de SKU-2002 (25 u., reorden 20) y `GET /replenishment/suggestions?status=OPEN&sku=SKU-2002` | Una sola sugerencia abierta (disponible 15 < 20); repetir eventos no la duplica | | |
| SAT-8 | US-03 | `POST …/{id}/approve` dos veces | 200 la primera; 409 `INVALID_TRANSITION` la segunda | | |
| SAT-9 | NFR disponibilidad | Detener RabbitMQ, reservar, reiniciar RabbitMQ | La reserva responde 201; el evento sale al volver el broker (`outbox_pending` baja a 0) | | |
| SAT-10 | NFR rendimiento | `k6 run perf/reserve.js` | p95 consulta < 200 ms, reserva < 400 ms | | |
