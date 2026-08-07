package dev.youneskaouani.vestige.gate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.gate.domain.ConditionType;
import dev.youneskaouani.vestige.gate.domain.GateCondition;
import dev.youneskaouani.vestige.gate.domain.GateInput;
import dev.youneskaouani.vestige.gate.domain.GateOutcome;
import dev.youneskaouani.vestige.gate.domain.GateStatus;
import dev.youneskaouani.vestige.gate.domain.QualityGateDefinition;
import dev.youneskaouani.vestige.gate.domain.QualityGateEvaluation;
import dev.youneskaouani.vestige.gate.domain.QualityGateEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link QualityGateEvaluator} itself is exercised in isolation by {@code QualityGateEvaluatorTest}
 * (dependency-free, part of {@code scripts/offline-verify.sh}'s core); this class instead checks
 * the persistence wrapping around it - that {@code GateConfigService}'s definition is what actually
 * gets evaluated, and that what commits to {@code quality_gate_evaluation} matches what the caller
 * gets back, using a real {@link ObjectMapper} rather than mocking Jackson's well-defined
 * behaviour.
 */
@ExtendWith(MockitoExtension.class)
class GateEvaluationServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private GateConfigService gateConfigService;

    @Mock private QualityGateEvaluationRepository evaluationRepository;

    private GateEvaluationService service;

    @BeforeEach
    void setUp() {
        service =
                new GateEvaluationService(
                        gateConfigService, evaluationRepository, new ObjectMapper());
    }

    @Test
    @DisplayName(
            "§7: a new CRITICAL issue fails the default gate's NEW_CRITICAL_ISSUES=0 condition")
    void failsTheGateOnANewCriticalIssue() {
        when(gateConfigService.getGate(PROJECT_ID)).thenReturn(QualityGateDefinition.defaultGate());
        GateInput.GateIssue newCritical =
                new GateInput.GateIssue(
                        "issue-1", Severity.CRITICAL, IssueStatus.OPEN, true, false);

        GateOutcome outcome =
                service.evaluate(ORG_ID, PROJECT_ID, RUN_ID, List.of(newCritical), NOW);

        assertThat(outcome.status()).isEqualTo(GateStatus.FAIL);
        assertThat(outcome.gateName()).isEqualTo("Vestige default");

        ArgumentCaptor<QualityGateEvaluation> captor =
                ArgumentCaptor.forClass(QualityGateEvaluation.class);
        verify(evaluationRepository).save(captor.capture());
        QualityGateEvaluation saved = captor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getAnalysisRunId()).isEqualTo(RUN_ID);
        assertThat(saved.getGateName()).isEqualTo("Vestige default");
        assertThat(saved.getStatus()).isEqualTo(GateStatus.FAIL);
        assertThat(saved.getEvaluatedAt()).isEqualTo(NOW);
        // §8/RunResponse embeds this string verbatim via @JsonRawValue - it must actually be the
        // serialised form of the outcome just returned, not a coincidentally-similar document.
        assertThat(saved.getResultJson())
                .contains("\"gateName\":\"Vestige default\"")
                .contains("\"status\":\"FAIL\"")
                .contains("issue-1");
    }

    @Test
    @DisplayName("no issues at all is a clean pass, and the persisted result says so")
    void passesTheGateWhenThereIsNothingToReport() {
        when(gateConfigService.getGate(PROJECT_ID)).thenReturn(QualityGateDefinition.defaultGate());

        GateOutcome outcome = service.evaluate(ORG_ID, PROJECT_ID, RUN_ID, List.of(), NOW);

        assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
        ArgumentCaptor<QualityGateEvaluation> captor =
                ArgumentCaptor.forClass(QualityGateEvaluation.class);
        verify(evaluationRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GateStatus.PASS);
        assertThat(captor.getValue().getResultJson()).contains("\"status\":\"PASS\"");
    }

    @Test
    @DisplayName("a silenced issue (won't-fix / false-positive) never counts against the gate")
    void ignoresIssuesTriagedAwayEvenWhenNewAndCritical() {
        when(gateConfigService.getGate(PROJECT_ID)).thenReturn(QualityGateDefinition.defaultGate());
        GateInput.GateIssue silenced =
                new GateInput.GateIssue(
                        "issue-1", Severity.CRITICAL, IssueStatus.RESOLVED_WONT_FIX, true, false);

        GateOutcome outcome = service.evaluate(ORG_ID, PROJECT_ID, RUN_ID, List.of(silenced), NOW);

        assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
    }

    @Test
    @DisplayName("evaluates against whatever GateConfigService resolves, not a hardcoded default")
    void evaluatesAgainstTheProjectsActualConfiguredGate() {
        when(gateConfigService.getGate(PROJECT_ID))
                .thenReturn(
                        new QualityGateDefinition(
                                "Lenient",
                                List.of(new GateCondition(ConditionType.NEW_CRITICAL_ISSUES, 10))));
        GateInput.GateIssue newCritical =
                new GateInput.GateIssue(
                        "issue-1", Severity.CRITICAL, IssueStatus.OPEN, true, false);

        GateOutcome outcome =
                service.evaluate(ORG_ID, PROJECT_ID, RUN_ID, List.of(newCritical), NOW);

        assertThat(outcome.gateName()).isEqualTo("Lenient");
        assertThat(outcome.status()).isEqualTo(GateStatus.PASS);
        verify(gateConfigService).getGate(PROJECT_ID);
    }
}
