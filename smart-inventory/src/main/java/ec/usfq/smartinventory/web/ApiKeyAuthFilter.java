package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autenticacion por API key (X-Api-Key) y autorizacion por scope.
 * 401 = no se pudo identificar al cliente; 403 = identificado pero sin el scope requerido.
 * La clave nunca se registra en logs.
 */
@Component
@Order(10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTR = "clientId";

    private final SecurityProperties props;
    private final ProblemWriter problems;

    public ApiKeyAuthFilter(SecurityProperties props, ProblemWriter problems) {
        this.props = props;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String presented = req.getHeader("X-Api-Key");
        Optional<SecurityProperties.ApiKey> match = props.keys().stream()
                .filter(k -> presented != null && constantTimeEquals(k.key(), presented))
                .findFirst();
        if (match.isEmpty()) {
            resp.setHeader("WWW-Authenticate", "ApiKey realm=\"smart-inventory\"");
            problems.write(resp, 401, "UNAUTHENTICATED", "Missing or invalid X-Api-Key");
            return;
        }
        String required = requiredScope(req.getMethod(), req.getRequestURI());
        if (required != null && !match.get().scopes().contains(required)) {
            problems.write(resp, 403, "FORBIDDEN", "Missing scope " + required);
            return;
        }
        req.setAttribute(CLIENT_ATTR, match.get().client());
        chain.doFilter(req, resp);
    }

    static String requiredScope(String method, String path) {
        boolean get = "GET".equals(method);
        if (path.startsWith("/api/v1/inventory") || path.startsWith("/api/v1/dashboard")) {
            return get ? "inventory:read" : null;
        }
        if (path.startsWith("/api/v1/reservations")) {
            return "POST".equals(method) ? "inventory:reserve" : null;
        }
        if (path.startsWith("/api/v1/replenishment")) {
            return get ? "replenishment:read" : "replenishment:approve";
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
