package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.application.DashboardQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardQueryService service;

    public DashboardController(DashboardQueryService service) {
        this.service = service;
    }

    @GetMapping("/inventory")
    public DashboardQueryService.View inventory() {
        return service.current();
    }
}
