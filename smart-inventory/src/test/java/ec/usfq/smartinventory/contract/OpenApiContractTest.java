package ec.usfq.smartinventory.contract;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contrato primero: el YAML debe ser valido y cumplir la politica del deber (codigos de error documentados,
 * 429 con Retry-After, scope por operacion). Corre sin levantar Spring ni BD.
 */
class OpenApiContractTest {

    private static OpenAPI load() {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        SwaggerParseResult r = new OpenAPIV3Parser().readLocation("openapi/openapi.yaml", null, opts);
        assertTrue(r.getMessages() == null || r.getMessages().isEmpty(), "OpenAPI invalido: " + r.getMessages());
        return r.getOpenAPI();
    }

    private static List<Operation> operations(OpenAPI api) {
        return api.getPaths().values().stream().flatMap(p -> p.readOperations().stream()).toList();
    }

    @Test
    void documentsAllRequiredEndpoints() {
        OpenAPI api = load();
        for (String p : List.of("/api/v1/inventory", "/api/v1/reservations", "/api/v1/replenishment/suggestions",
                "/api/v1/replenishment/suggestions/{id}/approve", "/api/v1/dashboard/inventory")) {
            assertNotNull(api.getPaths().get(p), "falta " + p);
        }
    }

    @Test
    void everyOperationDocumentsAuthAndRateLimitErrors() {
        for (Operation op : operations(load())) {
            for (String code : List.of("401", "403", "429")) {
                assertTrue(op.getResponses().containsKey(code), op.getOperationId() + " no documenta " + code);
            }
            assertNotNull(op.getExtensions().get("x-required-scope"), op.getOperationId() + " sin scope");
        }
    }

    @Test
    void reservationDocumentsIdempotencyAndConflictCodes() {
        PathItem p = load().getPaths().get("/api/v1/reservations");
        var responses = p.getPost().getResponses();
        for (String code : List.of("200", "201", "400", "404", "409", "422")) {
            assertTrue(responses.containsKey(code), "reservations no documenta " + code);
        }
        assertTrue(p.getPost().getParameters().stream().anyMatch(x -> "Idempotency-Key".equals(x.getName())
                && Boolean.TRUE.equals(x.getRequired())));
    }

    @Test
    void rateLimitResponseDeclaresRetryAfter() {
        var tooMany = load().getComponents().getResponses().get("TooManyRequests");
        assertTrue(tooMany.getHeaders().containsKey("Retry-After"));
    }

    @Test
    void errorsUseProblemJson() {
        var forbidden = load().getComponents().getResponses().get("Forbidden");
        assertTrue(forbidden.getContent().containsKey("application/problem+json"));
    }
}
