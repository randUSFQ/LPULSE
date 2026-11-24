# Runbook: "Las reservas no se ven en el dashboard"

**Alertas relacionadas:** `OutboxBacklog`, `ConsumerQueueAging`, `DeadLetterQueueNotEmpty` (ver `ops/alerts.yml`).
**Responsable de guardia:** definir por equipo. **Severidad:** page si `stale=true` > 5 min.

## 1. Síntoma y alcance
- `GET /api/v1/dashboard/inventory` devuelve filas con `stale: true` o un `processingDelayMs` creciente.
- Alcance: ¿un SKU o todos? Si solo falta uno, sospechar de un mensaje envenenado; si faltan todos, del broker o del consumidor.

## 2. Diagnóstico (en este orden; **no purgar mensajes como primer paso**)
1. **Correlación:** tomar el `requestId` de la reserva (cabecera `X-Request-Id` de la respuesta) y buscarlo en los logs JSON: debe aparecer en la API, en el publicador del outbox y en `DashboardConsumer`. El último servicio donde aparece indica dónde se detuvo.
2. **Salud de dependencias:** `GET /actuator/health/readiness` (DB y broker).
3. **Outbox:** `select count(*) from outbox where published_at is null;` o la métrica `smartinventory_outbox_pending`. Si crece → el publicador no llega al broker.
4. **Broker:** consola `:15672`: profundidad y antigüedad de `dashboard.q` / `replenishment.q`, tasa de reintentos y contenido de `inventory.dlq`.
5. **Trazas:** buscar el `traceId` del log para ver el span donde se consume el tiempo.

## 3. Mitigación segura
| Causa | Acción |
|---|---|
| Broker caído | Restaurar broker. El outbox conserva los eventos; el publicador reanuda solo. No reiniciar la API por esto. |
| Consumidor saturado | Subir `max-concurrency` o pausar productores temporalmente (bajar el rate limit). |
| Mensajes en DLQ | Inspeccionar el cuerpo y la causa en logs; corregir el consumidor o el dato **antes** de re-procesar. |
| Despliegue defectuoso | Revertir al tag anterior de la imagen (`sha-…`); el pipeline lo hace en staging si falla el smoke test. |

## 4. Recuperación
- Re-procesar la DLQ con *shovel* o re-publicación hacia el exchange `smartinventory.events`. Es **seguro** porque los consumidores deduplican por `eventId` (`processed_event`).
- Reconciliar: `reserved` por SKU en `batch` frente a `reservation_line`; el dashboard debe converger.
- Registrar aprendizaje: causa raíz, tiempo de detección/recuperación, mejora de alerta o prueba añadida.
