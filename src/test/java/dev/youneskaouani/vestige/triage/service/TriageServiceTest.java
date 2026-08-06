package dev.youneskaouani.vestige.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TriageServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private TriageEventAppender appender;

    private TriageService service;

    @BeforeEach
    void setUp() {
        service = new TriageService(issueRepository, appender);
    }

    @Test
    @DisplayName("silencing an issue moves its status and appends a matching audit entry")
    void appliesATriageDecisionAndAppendsAnAuditEntry() {
        UUID issueId = UUID.randomUUID();
        when(issueRepository.findById(issueId)).thenReturn(Optional.of(openIssue(issueId)));

        Issue result = service.applyTriage(
                ORG_ID, issueId, IssueStatus.RESOLVED_WONT_FIX, "younes", "accepted risk", NOW);

        assertThat(result.getStatus()).isEqualTo(IssueStatus.RESOLVED_WONT_FIX);
        verify(appender)
                .append(ORG_ID, issueId, "younes", IssueStatus.OPEN, IssueStatus.RESOLVED_WONT_FIX, "accepted risk", NOW);
    }

    @Test
    @DisplayName("§8: FALSE_POSITIVE/WONT_FIX require a justification, blank or absent both rejected")
    void requiresAJustificationToSilenceAnIssue() {
        UUID issueId = UUID.randomUUID();
        when(issueRepository.findById(issueId)).thenReturn(Optional.of(openIssue(issueId)));

        assertThatThrownBy(() -> service.applyTriage(
                        ORG_ID, issueId, IssueStatus.RESOLVED_FALSE_POSITIVE, "younes", null, NOW))
                .isInstanceOf(Problems.BadRequest.class);
        assertThatThrownBy(() -> service.applyTriage(
                        ORG_ID, issueId, IssueStatus.RESOLVED_FALSE_POSITIVE, "younes", "   ", NOW))
                .isInstanceOf(Problems.BadRequest.class);
        verifyNoInteractions(appender);
    }

    @Test
    @DisplayName("correcting a mistaken triage back to OPEN needs no justification")
    void doesNotRequireJustificationToClearATriageDecision() {
        UUID issueId = UUID.randomUUID();
        when(issueRepository.findById(issueId)).thenReturn(Optional.of(silencedIssue(issueId)));

        Issue result = service.applyTriage(ORG_ID, issueId, IssueStatus.OPEN, "younes", null, NOW);

        assertThat(result.getStatus()).isEqualTo(IssueStatus.OPEN);
        verify(appender)
                .append(
                        eq(ORG_ID),
                        eq(issueId),
                        eq("younes"),
                        eq(IssueStatus.RESOLVED_WONT_FIX),
                        eq(IssueStatus.OPEN),
                        any(),
                        eq(NOW));
    }

    @Test
    @DisplayName("setting an issue to the status it already has is a conflict, not a silent no-op")
    void rejectsATransitionToTheStatusTheIssueAlreadyHas() {
        UUID issueId = UUID.randomUUID();
        when(issueRepository.findById(issueId)).thenReturn(Optional.of(openIssue(issueId)));

        assertThatThrownBy(() -> service.applyTriage(ORG_ID, issueId, IssueStatus.OPEN, "younes", null, NOW))
                .isInstanceOf(Problems.Conflict.class);
        verifyNoInteractions(appender);
    }

    @Test
    @DisplayName("triaging an issue that does not exist (or is not this tenant's) is a 404")
    void aMissingIssueIsNotFound() {
        UUID issueId = UUID.randomUUID();
        when(issueRepository.findById(issueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyTriage(
                        ORG_ID, issueId, IssueStatus.RESOLVED_WONT_FIX, "younes", "why", NOW))
                .isInstanceOf(Problems.NotFound.class);
        verifyNoInteractions(appender);
    }

    private static Issue openIssue(UUID id) {
        return new Issue(
                id,
                ORG_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "java:S1234",
                Severity.MAJOR,
                "Do not do that",
                "src/main/java/Foo.java",
                null,
                10,
                UUID.randomUUID(),
                "abc123",
                NOW);
    }

    private static Issue silencedIssue(UUID id) {
        Issue issue = openIssue(id);
        issue.applyTriage(IssueStatus.RESOLVED_WONT_FIX, NOW);
        return issue;
    }
}
