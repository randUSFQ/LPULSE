package ec.usfq.smartinventory.ratelimit;

/** Contrato comun para comparar estrategias (token bucket vs ventana fija) con la misma suite de pruebas. */
public interface RateLimiter {

    record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    /** @param key clave de conteo (aqui: identificador del cliente autenticado) */
    Decision tryAcquire(String key);
}
