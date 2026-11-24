package ec.usfq.smartinventory.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Compara las dos estrategias con la misma rafaga y mide permitidos/bloqueados (evidencia del deber). */
class RateLimiterComparisonTest {

    static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long ms) {
            millis += ms;
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private static int burst(RateLimiter limiter, String key, int n) {
        int allowed = 0;
        for (int i = 0; i < n; i++) {
            if (limiter.tryAcquire(key).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void tokenBucketAllowsBurstThenRejectsWithRetryAfter() {
        var clock = new MutableClock(0);
        var limiter = new TokenBucketLimiter(5, 1.0, clock);
        assertEquals(5, burst(limiter, "c", 5));
        var rejected = limiter.tryAcquire("c");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() >= 1);
        clock.advance(2000);
        assertEquals(2, burst(limiter, "c", 5));   // 2 tokens repuestos
    }

    @Test
    void limitsAreIndependentPerClient() {
        var limiter = new TokenBucketLimiter(2, 1.0, new MutableClock(0));
        assertEquals(2, burst(limiter, "a", 5));
        assertEquals(2, burst(limiter, "b", 5));
    }

    @Test
    void fixedWindowRejectsAndResetsOnNextWindow() {
        var clock = new MutableClock(0);
        var limiter = new FixedWindowLimiter(3, Duration.ofSeconds(1), clock);
        assertEquals(3, burst(limiter, "c", 10));
        clock.advance(1000);
        assertEquals(3, burst(limiter, "c", 10));
    }

    @Test
    void fixedWindowAllowsDoubleBurstAtBoundaryButTokenBucketDoesNot() {
        // Mismo "presupuesto": 5 req/s. Rafaga de 5 al final de una ventana y 5 al inicio de la siguiente.
        var fixedClock = new MutableClock(900);
        var fixed = new FixedWindowLimiter(5, Duration.ofSeconds(1), fixedClock);
        int fixedAllowed = burst(fixed, "c", 5);
        fixedClock.advance(100);                       // t = 1000 ms -> nueva ventana
        fixedAllowed += burst(fixed, "c", 5);

        var bucketClock = new MutableClock(900);
        var bucket = new TokenBucketLimiter(5, 5.0, bucketClock);
        int bucketAllowed = burst(bucket, "c", 5);
        bucketClock.advance(100);
        bucketAllowed += burst(bucket, "c", 5);

        assertEquals(10, fixedAllowed, "ventana fija deja pasar 2x el limite en 100 ms");
        assertTrue(bucketAllowed <= 5, "token bucket respeta la tasa sostenida");
    }

    @Test
    void remainingHeaderValueDecreases() {
        var limiter = new TokenBucketLimiter(3, 1.0, new MutableClock(0));
        assertEquals(2, limiter.tryAcquire("c").remaining());
        assertEquals(1, limiter.tryAcquire("c").remaining());
        assertEquals(0, limiter.tryAcquire("c").remaining());
    }
}
