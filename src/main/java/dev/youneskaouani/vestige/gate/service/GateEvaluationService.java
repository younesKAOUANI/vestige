package dev.youneskaouani.vestige.gate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.gate.domain.GateInput;
import dev.youneskaouani.vestige.gate.domain.GateOutcome;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import dev.youneskaouani.vestige.gate.domain.QualityGateEvaluation;
import dev.youneskaouani.vestige.gate.domain.QualityGateEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wraps the pure {@link QualityGateEvaluator} with persistence: resolves the project's configured
 * gate, evaluates it, and writes the one-row-per-run {@link QualityGateEvaluation} (§7). Called by
 * {@code RunProcessingService} as the last step of a run's processing transaction, so a gate result
 * commits together with the run it was computed for or not at all - the same "all or nothing"
 * guarantee §4.1 states for the run row and its findings.
 */
@Service
public class GateEvaluationService {

    private final GateConfigService gateConfigService;
    private final QualityGateEvaluationRepository evaluationRepository;
    private final QualityGateEvaluator evaluator = new QualityGateEvaluator();
    private final ObjectMapper objectMapper;

    public GateEvaluationService(
            GateConfigService gateConfigService,
            QualityGateEvaluationRepository evaluationRepository,
            ObjectMapper objectMapper) {
        this.gateConfigService = gateConfigService;
        this.evaluationRepository = evaluationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GateOutcome evaluate(
            UUID organizationId,
            UUID projectId,
            UUID runId,
            List<GateInput.GateIssue> gateIssues,
            Instant now) {
        QualityGateDefinition definition = gateConfigService.getGate(projectId);
        GateOutcome outcome = evaluator.evaluate(definition, new GateInput(gateIssues));

        evaluationRepository.save(
                new QualityGateEvaluation(
                        UUID.randomUUID(),
                        organizationId,
                        projectId,
                        runId,
                        outcome.gateName(),
                        outcome.status(),
                        writeJson(outcome),
                        now));
        return outcome;
    }

    private String writeJson(GateOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (JsonProcessingException e) {
            // GateOutcome is a closed tree of records/enums/strings/longs - there is no code path
            // through it that Jackson cannot serialise.
            throw new IllegalStateException("GateOutcome must always be JSON-serialisable", e);
        }
    }
}
