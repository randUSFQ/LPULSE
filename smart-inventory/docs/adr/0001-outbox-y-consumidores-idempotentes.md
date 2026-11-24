# ADR-0001 · Outbox + consumidores idempotentes

**Estado:** aceptada · **Historias:** US-02, US-03, US-04

**Contexto.** Una reserva debe cambiar el saldo **y** avisar al dashboard y a reposición. Escribir en la BD y publicar al broker son dos sistemas: sin coordinación, un fallo entre ambos pierde el evento (o publica uno de un cambio revertido). 2PC/XA es costoso y frágil.

**Decisión.** El evento se inserta en la tabla `outbox` dentro de la misma transacción que el cambio (`OutboxWriter`, propagación `MANDATORY`). `OutboxPublisher` lee pendientes con `FOR UPDATE SKIP LOCKED`, publica con *publisher confirms* y recién entonces marca `published_at`. La entrega es **al-menos-una-vez**; cada consumidor inserta `(consumer, eventId)` en `processed_event` en la misma transacción que su efecto.

**Alternativas.** Publicar tras el commit (pierde eventos si cae el proceso); 2PC (acoplamiento y disponibilidad); CDC con Debezium (mejor a escala, excesivo para el curso).

**Costos.** Latencia de hasta `poll-ms` (500 ms); tabla `outbox` que crece (requiere purga de filas publicadas); consumo duplicado posible (mitigado).

**Prueba del beneficio.** `ReservationFlowIT.reservationWritesOutboxInTheSameTransaction` y `rejectedReservationRollsBackEverything`; SAT-9 (broker caído).
