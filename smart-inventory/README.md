# Smart Inventory · solución de referencia (Deber 2, Desarrollo de Software Moderno)

Recorrido vertical: **consultar stock → reservar con FEFO (idempotente) → evento por Outbox → dashboard y reposición por consumidores idempotentes**, con CI/CD DevSecOps y observabilidad.

## Ejecutar

```bash
docker compose up -d --build          # postgres, rabbitmq, app, prometheus, grafana
bash scripts/sat.sh                   # SAT automatizado (docs/sat.md)
k6 run perf/reserve.js                # carga controlada (p95 objetivo: consulta < 200 ms, reserva < 400 ms)
mvn verify                            # unitarias + contrato + integracion (Testcontainers, requiere Docker)
```

Swagger UI: `http://localhost:8080/swagger-ui.html` · Prometheus `:9090` · RabbitMQ `:15672` (guest/guest) · Grafana `:3000`.
Claves de **desarrollo** (`X-Api-Key`): `dev-ops-key`, `dev-fulfillment-key`, `dev-purchasing-key`. En staging se inyectan por variables de entorno del gestor de secretos; nunca se versionan.

```bash
curl -s -H 'X-Api-Key: dev-fulfillment-key' -H 'Idempotency-Key: demo-0001' -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1001","location":"BOD-UIO","quantity":35,"destination":"PEDIDO-1"}' localhost:8080/api/v1/reservations
```

## Mapa historia → implementación → prueba

| Historia | Endpoint / evento | Código | Prueba |
|---|---|---|---|
| US-01 | `GET /api/v1/inventory` | `InventoryController`, `InventoryQueryService` | SAT-1, contrato |
| US-02 | `POST /api/v1/reservations` | `ReservationService`, `FefoAllocationPolicy` | `FefoAllocationPolicyTest`, `ReservationFlowIT`, SAT-3..5 |
| US-03 | `GET/POST …/replenishment/suggestions` | `ReplenishmentConsumer`, `ReorderPointRule`, `ReplenishmentService` | `ReorderPointRuleTest`, SAT-7/8 |
| US-04 | `GET /api/v1/dashboard/inventory` | `DashboardConsumer`, `DashboardQueryService` | SAT-6, SAT-9 |

## Arquitectura (resumen)

`web` (filtros: correlación → autenticación/scope → rate limit; controladores; `problem+json`) → `application` (casos de uso transaccionales) → `domain` (FEFO, reposición: Java puro, sin Spring) · `ratelimit` (token bucket / ventana fija) · `messaging` (Outbox, publicador, consumidores, idempotencia).
Decisiones en `docs/adr/`. Evento versionado en `docs/events/`. Contrato en `openapi/openapi.yaml`. Runbook en `docs/runbook.md`.

## Patrones y SOLID (dónde está cada uno)

| Patrón | Ubicación | Beneficio probado |
|---|---|---|
| Strategy | `AllocationPolicy`, `ReplenishmentRule`, `RateLimiter` | Prueba unitaria de FEFO sin BD; comparación de estrategias de rate limit |
| Ports & Adapters | `domain`/`application` no importan broker; `messaging` y JDBC son adaptadores | Dominio probado sin infraestructura |
| Outbox + consumidor idempotente | `OutboxWriter`, `OutboxPublisher`, `ProcessedEvents` | `ReservationFlowIT` (atomicidad), SAT-9 |
| Unit of Work | `@Transactional` en el caso de uso | Rollback completo ante `InsufficientStockException` |

Se omitió *Repository* como capa separada a propósito: con SQL explícito y 4 consultas, una capa extra sería un patrón "para cumplir la lista" (ver ADR). Los equipos pueden extraerlo si añaden más agregados.

## Qué se verificó aquí y qué no

| Parte | Estado |
|---|---|
| Dominio (FEFO, reposición) y rate limiters: 15 pruebas | **Ejecutadas y aprobadas** (compiladas con `javac`, sin Maven) |
| `openapi.yaml`, JSON Schema del evento | **Validados sintácticamente** y sus `$ref` resuelven (PyYAML); `OpenApiContractTest` no se ejecutó |
| Capa Spring (filtros, servicios, outbox, consumidores), `ReservationFlowIT`, `OpenApiContractTest`, Dockerfile, compose, workflow | **Escritos pero NO compilados ni ejecutados**: el entorno de generación no alcanzaba Maven Central ni tenía broker/BD. Correr `mvn verify` primero y corregir lo que aparezca |

## DAST autenticado
El baseline de ZAP apunta a `/v3/api-docs` (público). Para escanear endpoints protegidos, crear un *context* de ZAP con la cabecera `X-Api-Key` de la cuenta de prueba de staging y registrar hallazgos (severidad, evidencia no sensible, decisión, responsable) en `docs/security-findings.md`.

## Límites conocidos (para discutir en clase)
- Rate limit en memoria por instancia; no limita peticiones sin credenciales (401 antes del límite).
- Autenticación por API key con scopes: suficiente para el laboratorio; producción usaría OIDC/JWT.
- `outbox` y `idempotency_key` crecen: falta job de purga.
- Datos semilla con fechas de nov-2026.
