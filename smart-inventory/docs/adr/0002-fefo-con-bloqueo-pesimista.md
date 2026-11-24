# ADR-0002 · FEFO atómico con `SELECT … FOR UPDATE` y patrón Strategy

**Estado:** aceptada · **Historia:** US-02

**Decisión.** La reserva bloquea los lotes del SKU/ubicación (`FOR UPDATE`, orden por `expires_on, id` para evitar deadlocks entre transacciones), delega el reparto a `AllocationPolicy` (Strategy; implementación `FefoAllocationPolicy`) y actualiza saldos. Un `CHECK (reserved <= physical)` es la última defensa contra sobreasignación.

**Alternativas.** Bloqueo optimista con `version` (más reintentos bajo contención alta sobre el mismo SKU); `UPDATE … WHERE available >= ?` por lote sin política (acopla FEFO a SQL y dificulta probar la regla).

**Costos.** Las reservas del mismo SKU/ubicación se serializan (aceptable: la sección crítica es corta). Se mide en el escenario `commands` de k6.

**Prueba.** Unitarias de `FefoAllocationPolicyTest` (la regla sin BD) y `ReservationFlowIT.concurrentReservationsNeverOversell` (50 hilos, 100 u. → exactamente 33 reservas de 3).

**SOLID.** SRP: política separada del servicio. OCP: nueva política sin tocar `ReservationService`. DIP: el servicio depende de la interfaz, el *wiring* vive en `AppConfig`.
