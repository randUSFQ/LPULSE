package ec.usfq.smartinventory.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket: permite rafagas hasta {@code capacity} y una tasa sostenida de {@code refillPerSecond}.
 * Nota educativa: los buckets no se evictan; en produccion usar una cache con expiracion (p. ej. Caffeine)
 * o un contador compartido (Redis) si hay mas de una instancia.
 */
public final class TokenBucketLimiter implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastMillis;

        Bucket(double tokens, long lastMillis) {
            this.tokens = tokens;
            this.lastMillis = lastMillis;
        }
    }

    private final int capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int capacity, double refillPerSecond, Clock clock) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be > 0");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = clock.millis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            double refill = (now - b.lastMillis) / 1000.0 * refillPerSecond;
            b.tokens = Math.min(capacity, b.tokens + refill);
            b.lastMillis = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return new Decision(true, capacity, (int) Math.floor(b.tokens), 0);
            }
            long retry = Math.max(1, (long) Math.ceil((1.0 - b.tokens) / refillPerSecond));
            return new Decision(false, capacity, 0, retry);
        }
    }
}
