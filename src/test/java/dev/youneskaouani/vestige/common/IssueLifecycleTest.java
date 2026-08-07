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
        assertThat(IssueLifecycle.afterSighting(IssueStatus.REOPENED))
                .isEqualTo(IssueStatus.REOPENED);
    }

    @Test
    @DisplayName("a fixed issue that is sighted again is reopened, not opened afresh")
    void sightingReopensAFixedIssue() {
        assertThat(IssueLifecycle.afterSighting(IssueStatus.RESOLVED_FIXED))
                .isEqualTo(IssueStatus.REOPENED);
        assertThat(IssueLifecycle.isReopening(IssueStatus.RESOLVED_FIXED, IssueStatus.REOPENED))
                .isTrue();
        assertThat(IssueLifecycle.isReopening(IssueStatus.OPEN, IssueStatus.OPEN)).isFalse();
    }

    @Test
    @DisplayName("an outstanding issue that is no longer reported is auto-resolved as fixed")
    void disappearanceResolves() {
        assertThat(IssueLifecycle.afterDisappearance(IssueStatus.OPEN))
                .isEqualTo(IssueStatus.RESOLVED_FIXED);
        assertThat(IssueLifecycle.afterDisappearance(IssueStatus.REOPENED))
                .isEqualTo(IssueStatus.RESOLVED_FIXED);
        assertThat(IssueLifecycle.afterDisappearance(IssueStatus.RESOLVED_FIXED))
                .isEqualTo(IssueStatus.RESOLVED_FIXED);
    }

    @ParameterizedTest
    @EnumSource(names = {"RESOLVED_FALSE_POSITIVE", "RESOLVED_WONT_FIX"})
    @DisplayName(
            "the matcher never overwrites a human triage decision, on sighting or on disappearance")
    void neverOverwritesAHumanDecision(IssueStatus decided) {
        assertThat(IssueLifecycle.afterSighting(decided)).isEqualTo(decided);
        assertThat(IssueLifecycle.afterDisappearance(decided)).isEqualTo(decided);
    }

    @Test
    @DisplayName("classifies which statuses are outstanding and which are silenced")
    void classifiesStatuses() {
        assertThat(IssueStatus.OPEN.isOutstanding()).isTrue();
        assertThat(IssueStatus.REOPENED.isOutstanding()).isTrue();
        assertThat(IssueStatus.RESOLVED_FIXED.isOutstanding()).isFalse();
        assertThat(IssueStatus.RESOLVED_WONT_FIX.isSilenced()).isTrue();
        assertThat(IssueStatus.RESOLVED_FALSE_POSITIVE.isSilenced()).isTrue();
        assertThat(IssueStatus.OPEN.isSilenced()).isFalse();
        assertThat(IssueStatus.RESOLVED_FALSE_POSITIVE.requiresTriage()).isTrue();
        assertThat(IssueStatus.RESOLVED_WONT_FIX.requiresTriage()).isTrue();
        assertThat(IssueStatus.RESOLVED_FIXED.requiresTriage()).isFalse();
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
