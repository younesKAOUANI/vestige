package dev.youneskaouani.vestige.gate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.gate.domain.ConditionType;
import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.QualityGate;
import dev.youneskaouani.vestige.gate.domain.QualityGateCondition;
import dev.youneskaouani.vestige.gate.domain.QualityGateConditionRepository;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import dev.youneskaouani.vestige.gate.domain.QualityGateRepository;
import dev.youneskaouani.vestige.tenancy.domain.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GateConfigServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private QualityGateRepository gateRepository;

    @Mock private QualityGateConditionRepository conditionRepository;

    @Mock private ProjectRepository projectRepository;

    private GateConfigService service;

    @BeforeEach
    void setUp() {
        service = new GateConfigService(gateRepository, conditionRepository, projectRepository);
    }

    @Test
    @DisplayName("§7: a project with no configured gate gets the documented default")
    void fallsBackToTheDefaultGateWhenNoneIsConfigured() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(gateRepository.findByProjectId(projectId)).thenReturn(Optional.empty());

        QualityGateDefinition gate = service.getGate(projectId);

        assertThat(gate).isEqualTo(QualityGateDefinition.defaultGate());
    }

    @Test
    @DisplayName(
            "a project's configured gate is assembled from its stored conditions, in position order")
    void assemblesAConfiguredGateFromItsConditions() {
        UUID projectId = UUID.randomUUID();
        QualityGate gate = new QualityGate(UUID.randomUUID(), ORG_ID, projectId, "Strict", NOW);
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(gateRepository.findByProjectId(projectId)).thenReturn(Optional.of(gate));
        when(conditionRepository.findAllByQualityGateIdOrderByPosition(gate.getId()))
                .thenReturn(
                        List.of(
                                new QualityGateCondition(
                                        UUID.randomUUID(),
                                        ORG_ID,
                                        gate.getId(),
                                        ConditionType.NEW_CRITICAL_ISSUES,
                                        0,
                                        0),
                                new QualityGateCondition(
                                        UUID.randomUUID(),
                                        ORG_ID,
                                        gate.getId(),
                                        ConditionType.TOTAL_BLOCKER_ISSUES,
                                        2,
                                        1)));

        QualityGateDefinition definition = service.getGate(projectId);

        assertThat(definition.name()).isEqualTo("Strict");
        assertThat(definition.conditions())
                .containsExactly(
                        new GateCondition(ConditionType.NEW_CRITICAL_ISSUES, 0),
                        new GateCondition(ConditionType.TOTAL_BLOCKER_ISSUES, 2));
    }

    @Test
    @DisplayName(
            "reading the gate of a project that does not exist (or is not this tenant's) is a 404")
    void readingAMissingProjectsGateIsNotFound() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(false);

        assertThatThrownBy(() -> service.getGate(projectId)).isInstanceOf(Problems.NotFound.class);
        verify(gateRepository, never()).findByProjectId(any());
    }

    @Test
    @DisplayName(
            "PUT replaces a first-ever gate: a new QualityGate row, and one condition row per entry")
    void createsAGateAndItsConditionsOnFirstConfiguration() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(gateRepository.findByProjectId(projectId)).thenReturn(Optional.empty());
        when(gateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        QualityGateDefinition requested =
                new QualityGateDefinition(
                        "Custom",
                        List.of(
                                new GateCondition(ConditionType.NEW_ISSUES_TOTAL, 3),
                                new GateCondition(ConditionType.REOPENED_ISSUES, 0)));

        QualityGateDefinition result = service.replaceGate(ORG_ID, projectId, requested, NOW);

        assertThat(result).isEqualTo(requested);
        ArgumentCaptor<QualityGate> gateCaptor = ArgumentCaptor.forClass(QualityGate.class);
        verify(gateRepository).save(gateCaptor.capture());
        assertThat(gateCaptor.getValue().getName()).isEqualTo("Custom");

        ArgumentCaptor<QualityGateCondition> conditionCaptor =
                ArgumentCaptor.forClass(QualityGateCondition.class);
        verify(conditionRepository, times(2)).save(conditionCaptor.capture());
        List<QualityGateCondition> saved = conditionCaptor.getAllValues();
        assertThat(saved.get(0).getConditionType()).isEqualTo(ConditionType.NEW_ISSUES_TOTAL);
        assertThat(saved.get(0).getPosition()).isZero();
        assertThat(saved.get(1).getConditionType()).isEqualTo(ConditionType.REOPENED_ISSUES);
        assertThat(saved.get(1).getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "PUT on an already-configured gate renames the existing row rather than creating a second one")
    void renamesAnExistingGateInsteadOfDuplicatingIt() {
        UUID projectId = UUID.randomUUID();
        QualityGate existing =
                new QualityGate(UUID.randomUUID(), ORG_ID, projectId, "Old name", NOW);
        when(projectRepository.existsById(projectId)).thenReturn(true);
        when(gateRepository.findByProjectId(projectId)).thenReturn(Optional.of(existing));
        QualityGateDefinition requested =
                new QualityGateDefinition(
                        "New name", List.of(new GateCondition(ConditionType.REOPENED_ISSUES, 1)));

        service.replaceGate(ORG_ID, projectId, requested, NOW);

        assertThat(existing.getName()).isEqualTo("New name");
        verify(gateRepository, never()).save(any());
        verify(conditionRepository).deleteAllByQualityGateId(existing.getId());
    }

    @Test
    @DisplayName("replacing the gate of a project that does not exist is a 404, nothing written")
    void replacingAMissingProjectsGateIsNotFound() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.existsById(projectId)).thenReturn(false);
        QualityGateDefinition requested = QualityGateDefinition.defaultGate();

        assertThatThrownBy(() -> service.replaceGate(ORG_ID, projectId, requested, NOW))
                .isInstanceOf(Problems.NotFound.class);
        verify(gateRepository, never()).findByProjectId(any());
        verify(conditionRepository, never()).deleteAllByQualityGateId(any());
    }
}
