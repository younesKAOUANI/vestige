package dev.youneskaouani.vestige.issues.api;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.common.web.PageResponse;
import dev.youneskaouani.vestige.issues.service.IssueHistoryService;
import dev.youneskaouani.vestige.issues.service.IssueQueryService;
import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import dev.youneskaouani.vestige.triage.service.TriageService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Issue resource's whole API surface (§8): list, triage, history - grouped by URL rather than
 * by which service package backs each one, since that is how a REST client thinks about {@code
 * /api/v1/issues/**} and {@code /api/v1/projects/{id}/issues}.
 */
@RestController
@RequestMapping("/api/v1")
public class IssueController {

    private final IssueQueryService queryService;
    private final IssueHistoryService historyService;
    private final TriageService triageService;

    public IssueController(
            IssueQueryService queryService, IssueHistoryService historyService, TriageService triageService) {
        this.queryService = queryService;
        this.historyService = historyService;
        this.triageService = triageService;
    }

    @GetMapping("/projects/{projectId}/issues")
    public PageResponse<IssueResponse> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) IssueStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String rule,
            @RequestParam(name = "sinceRun", required = false) UUID sinceRun,
            @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(
                queryService.search(projectId, status, severity, rule, sinceRun, pageable), IssueResponse::of);
    }

    @PatchMapping("/issues/{id}")
    public IssueResponse triage(@PathVariable UUID id, @Valid @RequestBody TriagePatchRequest request) {
        UUID organizationId = TenantContext.require();
        return IssueResponse.of(triageService.applyTriage(
                organizationId, id, request.status(), request.actor(), request.justification(), Instant.now()));
    }

    @GetMapping("/issues/{id}/history")
    public IssueHistoryResponse history(@PathVariable UUID id) {
        return IssueHistoryResponse.of(historyService.history(id));
    }
}
