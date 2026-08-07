package dev.youneskaouani.vestige.gate.api;

import dev.youneskaouani.vestige.gate.service.GateConfigService;
import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET}/{@code PUT /api/v1/projects/{id}/gate} (§8). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/gate")
public class GateController {

    private final GateConfigService gateConfigService;

    public GateController(GateConfigService gateConfigService) {
        this.gateConfigService = gateConfigService;
    }

    @GetMapping
    public GateConfigResponse get(@PathVariable UUID projectId) {
        return GateConfigResponse.of(gateConfigService.getGate(projectId));
    }

    @PutMapping
    public GateConfigResponse replace(
            @PathVariable UUID projectId, @Valid @RequestBody GateConfigRequest request) {
        UUID organizationId = TenantContext.require();
        return GateConfigResponse.of(
                gateConfigService.replaceGate(
                        organizationId, projectId, request.toDefinition(), Instant.now()));
    }
}
