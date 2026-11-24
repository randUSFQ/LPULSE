package ec.usfq.smartinventory.config;

import ec.usfq.smartinventory.domain.AllocationPolicy;
import ec.usfq.smartinventory.domain.FefoAllocationPolicy;
import ec.usfq.smartinventory.domain.ReorderPointRule;
import ec.usfq.smartinventory.domain.ReplenishmentRule;
import ec.usfq.smartinventory.ratelimit.FixedWindowLimiter;
import ec.usfq.smartinventory.ratelimit.RateLimiter;
import ec.usfq.smartinventory.ratelimit.TokenBucketLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: aqui (y solo aqui) se eligen las implementaciones concretas (DIP). */
@Configuration
public class AppConfig {

    @ConfigurationProperties("app.rate-limit")
    public record RateLimitProperties(String strategy, int capacity, double refillPerSecond,
                                      int limit, int windowSeconds) {
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AllocationPolicy allocationPolicy() {
        return new FefoAllocationPolicy();
    }

    @Bean
    ReplenishmentRule replenishmentRule() {
        return new ReorderPointRule();
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties p, Clock clock) {
        return switch (p.strategy()) {
            case "token-bucket" -> new TokenBucketLimiter(p.capacity(), p.refillPerSecond(), clock);
            case "fixed-window" -> new FixedWindowLimiter(p.limit(), Duration.ofSeconds(p.windowSeconds()), clock);
            default -> throw new IllegalArgumentException("Unknown rate-limit strategy: " + p.strategy());
        };
    }
}
