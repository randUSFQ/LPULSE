package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.application.ReplenishmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/replenishment/suggestions")
@Validated
public class ReplenishmentController {

    private final ReplenishmentService service;

    public ReplenishmentController(ReplenishmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<ReplenishmentService.SuggestionView> list(
            @RequestParam(required = false) @Pattern(regexp = "OPEN|APPROVED") String status,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sku,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String supplier,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.list(status, sku, supplier, limit);
    }

    @PostMapping("/{id}/approve")
    public ReplenishmentService.SuggestionView approve(@PathVariable UUID id, HttpServletRequest req) {
        return service.approve(id, (String) req.getAttribute(ApiKeyAuthFilter.CLIENT_ATTR),
                MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
