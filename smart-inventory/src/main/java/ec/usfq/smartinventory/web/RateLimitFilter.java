package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Corre DESPUES de autenticar: la clave de conteo es el cliente, no la IP. Responde 429 con Retry-After. */
@Component
@Order(20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ProblemWriter problems;
    private final MeterRegistry metrics;

    public RateLimitFilter(RateLimiter limiter, ProblemWriter problems, MeterRegistry metrics) {
        this.limiter = limiter;
        this.problems = problems;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String client = (String) req.getAttribute(ApiKeyAuthFilter.CLIENT_ATTR);
        RateLimiter.Decision d = limiter.tryAcquire(client);
        resp.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        resp.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            metrics.counter("smartinventory.ratelimit.rejected", "client", client).increment();
            resp.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            problems.write(resp, 429, "RATE_LIMITED", "Too many requests; retry after " + d.retryAfterSeconds() + "s");
            return;
        }
        chain.doFilter(req, resp);
    }
}
