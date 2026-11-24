# ADR-0003 · Rate limiting: token bucket por defecto, ventana fija como alternativa comparada

**Estado:** aceptada · **NFR:** seguridad/disponibilidad

**Decisión.** Interfaz `RateLimiter` con dos implementaciones seleccionables por configuración (`app.rate-limit.strategy`). Clave de conteo: cliente autenticado (no IP, que en NAT agrupa clientes distintos). Se aplica **después** de autenticar. Al exceder: `429` + `Retry-After` + `X-RateLimit-*` + `problem+json`.

**Comparación medida** (`RateLimiterComparisonTest`): con el mismo presupuesto de 5 req/s, una ráfaga de 5 al final de una ventana y otra de 5 al inicio de la siguiente deja pasar **10** en 100 ms con ventana fija, y **≤ 5** con token bucket. La ventana fija es más simple y barata pero produce estas ráfagas dobles en el borde; el token bucket permite ráfagas controladas y una tasa sostenida.

**Falsos bloqueos.** Se miden como `smartinventory.ratelimit.rejected{client}` frente al total de peticiones del cliente, repitiendo el escenario k6 con cada estrategia (`RATE_LIMIT_STRATEGY`) y comparando 429 sobre tráfico legítimo dentro de la ráfaga permitida.

**Costos / límites.** Estado en memoria por instancia (con N réplicas el límite efectivo es N×); en producción, contador compartido (Redis). Las peticiones sin credenciales no consumen cuota (se rechazan antes con 401): proteger con límite por IP en el gateway.
