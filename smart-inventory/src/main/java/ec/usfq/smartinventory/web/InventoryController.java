package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.application.InventoryQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@Validated
public class InventoryController {

    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private final InventoryQueryService service;

    public InventoryController(InventoryQueryService service) {
        this.service = service;
    }

    @GetMapping
    public InventoryQueryService.PageResponse<InventoryQueryService.StockItem> list(
            @RequestParam(required = false) @Pattern(regexp = ID) String sku,
            @RequestParam(required = false) @Pattern(regexp = ID) String location,
            @RequestParam(required = false) @Pattern(regexp = ID) String lot,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "expiresOn") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String order) {
        return service.search(sku, location, lot, page, size, sort, "desc".equals(order));
    }
}
