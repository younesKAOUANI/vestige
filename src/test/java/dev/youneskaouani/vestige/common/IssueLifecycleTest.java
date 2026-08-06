package dev.youneskaouani.vestige.common;

import static org.assertj.core.api.Assertions.assertThat;

import dev.youneskaouani.vestige.common.domain.IssueLifecycle;
import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IssueLifecycleTest {

    @Test
    @DisplayName("an open issue that is sighted again stays open")
    void sightingKeepsAnOpenIssueOpen() {
        assertThat(IssueLifecycle.afterSighting(IssueStatus.OPEN)).isEqualTo(IssueStatus.OPEN);
        assertThat(IssueLifecycle.afterSighting(IssueStatus.REOPENED)).isEqualTo(IssueStatus.REOPENED);
    }

    @Test
    @DisplayName("a resolved issue that is sighted again is reopened, not opened afresh")
    void sightingReopensAResolvedIssue() {
        assertThat(IssueLifecycle.afterSighting(IssueStatus.RESOLVED)).isEqualTo(IssueStatus.REOPENED);
        assertThat(IssueLifecycle.isReopening(IssueStatus.RESOLVED, IssueStatus.REOPENED)).isTrue();
        assertThat(IssueLifecycle.isReopening(IssueStatus.OPEN, IssueStatus.OPEN)).isFalse();
    }

    @Test
    @DisplayName("an issue that is no longer reported is resolved")
    void disappearanceResolves() {
        assertThat(IssueLifecycle.afterDisappearance(IssueStatus.OPEN)).isEqualTo(IssueStatus.RESOLVED);
        assertThat(IssueLifecycle.afterDisappearance(IssueStatus.REOPENED)).isEqualTo(IssueStatus.RESOLVED);
        assertThat(IssueLifecycle.afterDisappearance(IssueStatus.RESOLVED)).isEqualTo(IssueStatus.RESOLVED);
    }

    @ParameterizedTest
    @EnumSource(names = {"ACCEPTED", "FALSE_POSITIVE"})
    @DisplayName("the pipeline never overwrites a human triage decision")
    void neverOverwritesAHumanDecision(IssueStatus decided) {
        assertThat(IssueLifecycle.afterSighting(decided)).isEqualTo(decided);
        assertThat(IssueLifecycle.afterDisappearance(decided)).isEqualTo(decided);
    }

    @Test
    @DisplayName("classifies which statuses are outstanding and which are silenced")
    void classifiesStatuses() {
        assertThat(IssueStatus.OPEN.isOutstanding()).isTrue();
        assertThat(IssueStatus.REOPENED.isOutstanding()).isTrue();
        assertThat(IssueStatus.RESOLVED.isOutstanding()).isFalse();
        assertThat(IssueStatus.ACCEPTED.isSilenced()).isTrue();
        assertThat(IssueStatus.FALSE_POSITIVE.isSilenced()).isTrue();
        assertThat(IssueStatus.OPEN.isSilenced()).isFalse();
    }

    @Test
    @DisplayName("ranks severities and maps SARIF levels onto them")
    void ranksSeverities() {
        assertThat(Severity.BLOCKER.isAtLeast(Severity.CRITICAL)).isTrue();
        assertThat(Severity.MAJOR.isAtLeast(Severity.CRITICAL)).isFalse();
        assertThat(Severity.INFO.isAtLeast(Severity.INFO)).isTrue();

        assertThat(Severity.fromSarif("error", null)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.fromSarif("warning", null)).isEqualTo(Severity.MAJOR);
        assertThat(Severity.fromSarif("note", null)).isEqualTo(Severity.MINOR);
        assertThat(Severity.fromSarif("none", null)).isEqualTo(Severity.INFO);
        assertThat(Severity.fromSarif(null, null)).isEqualTo(Severity.MAJOR);
        assertThat(Severity.fromSarif("nonsense", null)).isEqualTo(Severity.MAJOR);
        assertThat(Severity.fromSarif("warning", 9.5)).isEqualTo(Severity.BLOCKER);
        assertThat(Severity.fromSarif("warning", 5.0)).isEqualTo(Severity.MAJOR);
    }
}
