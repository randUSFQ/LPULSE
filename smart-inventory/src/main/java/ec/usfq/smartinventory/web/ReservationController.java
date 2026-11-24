package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.application.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
@Validated
public class ReservationController {

    public record ReservationRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String location,
            @Min(1) @Max(100_000) int quantity,
            @NotBlank @Size(max = 64) String destination) {
    }

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /** 201 en la primera ejecucion; 200 + Idempotent-Replay si es un reintento con la misma clave. */
    @PostMapping
    public ResponseEntity<ReservationService.Result> create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody ReservationRequest body,
            HttpServletRequest req) {
        var cmd = new ReservationService.Command((String) req.getAttribute(ApiKeyAuthFilter.CLIENT_ATTR),
                idempotencyKey, body.sku(), body.location(), body.quantity(), body.destination(),
                MDC.get(CorrelationIdFilter.MDC_KEY));
        var result = service.reserve(cmd);
        return result.replay()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(result)
                : ResponseEntity.status(201).body(result);
    }
}
