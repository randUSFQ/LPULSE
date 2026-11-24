package ec.usfq.smartinventory.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ventana fija: simple y barata, pero permite hasta 2x el limite alrededor del borde de la ventana
 * (limit al final de una ventana + limit al inicio de la siguiente). Ver RateLimiterComparisonTest.
 */
public final class FixedWindowLimiter implements RateLimiter {

    private static final class State {
        long windowId = Long.MIN_VALUE;
        int count;
    }

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public FixedWindowLimiter(int limit, Duration window, Clock clock) {
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit and window must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = clock.millis();
        long windowId = now / windowMillis;
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            if (s.windowId != windowId) {
                s.windowId = windowId;
                s.count = 0;
            }
            if (s.count < limit) {
                s.count++;
                return new Decision(true, limit, limit - s.count, 0);
            }
            long windowEnd = (windowId + 1) * windowMillis;
            long retry = Math.max(1, (long) Math.ceil((windowEnd - now) / 1000.0));
            return new Decision(false, limit, 0, retry);
        }
    }
}
