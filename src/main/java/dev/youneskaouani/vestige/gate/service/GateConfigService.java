package dev.youneskaouani.vestige.gate.service;

import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.QualityGate;
import dev.youneskaouani.vestige.gate.domain.QualityGateCondition;
import dev.youneskaouani.vestige.gate.domain.QualityGateConditionRepository;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import dev.youneskaouani.vestige.gate.domain.QualityGateRepository;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code GET}/{@code PUT /api/v1/projects/{id}/gate} (§8) and is the single place that
 * resolves "the gate a project actually evaluates against" - {@code GateEvaluationService} depends
 * on this class rather than duplicating the default-gate fallback.
 */
@Service
public class GateConfigService {

    private final QualityGateRepository gateRepository;
    private final QualityGateConditionRepository conditionRepository;
    private final ProjectRepository projectRepository;

    public GateConfigService(
            QualityGateRepository gateRepository,
            QualityGateConditionRepository conditionRepository,
            ProjectRepository projectRepository) {
        this.gateRepository = gateRepository;
        this.conditionRepository = conditionRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * A project's configured gate, or {@link QualityGateDefinition#defaultGate()} when it has never
     * configured one - "the gate a project gets until someone configures one" (§7).
     */
    @Transactional(readOnly = true)
    public QualityGateDefinition getGate(UUID projectId) {
        requireProject(projectId);
        return gateRepository
                .findByProjectId(projectId)
                .map(this::toDefinition)
                .orElseGet(QualityGateDefinition::defaultGate);
    }

    /** Replaces a project's gate wholesale - {@code PUT} is a full replacement, not a patch. */
    @Transactional
    public QualityGateDefinition replaceGate(
            UUID organizationId, UUID projectId, QualityGateDefinition requested, Instant now) {
        requireProject(projectId);

        QualityGate gate = gateRepository
                .findByProjectId(projectId)
                .map(existing -> {
                    existing.rename(requested.name(), now);
                    return existing;
                })
                .orElseGet(() -> gateRepository.save(
                        new QualityGate(UUID.randomUUID(), organizationId, projectId, requested.name(), now)));

        conditionRepository.deleteAllByQualityGateId(gate.getId());
        int position = 0;
        for (GateCondition condition : requested.conditions()) {
            conditionRepository.save(new QualityGateCondition(
                    UUID.randomUUID(), organizationId, gate.getId(), condition.type(), condition.threshold(), position));
            position++;
        }
        return requested;
    }

    private QualityGateDefinition toDefinition(QualityGate gate) {
        var conditions = conditionRepository.findAllByQualityGateIdOrderByPosition(gate.getId()).stream()
                .map(QualityGateCondition::toGateCondition)
                .toList();
        return new QualityGateDefinition(gate.getName(), conditions);
    }

    private void requireProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new Problems.NotFound("Project", projectId);
        }
    }
}
