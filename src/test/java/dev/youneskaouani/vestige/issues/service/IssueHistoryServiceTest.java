package dev.youneskaouani.vestige.issues.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.youneskaouani.vestige.common.domain.IssueStatus;
import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.common.hash.HashChain;
import dev.youneskaouani.vestige.issues.domain.Finding;
import dev.youneskaouani.vestige.issues.domain.FindingRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
import dev.youneskaouani.vestige.issues.domain.MatchRung;
import dev.youneskaouani.vestige.triage.domain.TriageEvent;
import dev.youneskaouani.vestige.triage.domain.TriageEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private IssueRepository issueRepository;

    @Mock private FindingRepository findingRepository;

    @Mock private TriageEventRepository triageEventRepository;

    private IssueHistoryService service;

    @BeforeEach
    void setUp() {
        service =
                new IssueHistoryService(issueRepository, findingRepository, triageEventRepository);
    }

    @Test
    @DisplayName(
            "GET .../history (§8): the issue plus its full finding and triage timelines, oldest first")
    void assemblesTheFullHistoryOfAnExistingIssue() {
        UUID issueId = UUID.randomUUID();
        Issue issue = openIssue(issueId);
        List<Finding> findings = List.of(findingOn(issueId));
        List<TriageEvent> events = List.of(triageEventOn(issueId));

        when(issueRepository.findById(issueId)).thenReturn(Optional.of(issue));
        when(findingRepository.findAllByIssueIdOrderBySeq(issueId)).thenReturn(findings);
        when(triageEventRepository.findAllByIssueIdOrderBySequenceNumberAsc(issueId))
                .thenReturn(events);

        IssueHistory history = service.history(issueId);

        assertThat(history.issue()).isSameAs(issue);
        assertThat(history.findings()).isSameAs(findings);
        assertThat(history.triageEvents()).isSameAs(events);
    }

    @Test
    @DisplayName(
            "an issue that does not exist (or is not this tenant's) is a 404, findings/events never queried")
    void aMissingIssueIsNotFound() {
        UUID issueId = UUID.randomUUID();
        when(issueRepository.findById(issueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(issueId)).isInstanceOf(Problems.NotFound.class);
        verifyNoInteractions(findingRepository, triageEventRepository);
    }

    private static Issue openIssue(UUID id) {
        return new Issue(
                id,
                UUID.randomUUID(),
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

    private static Finding findingOn(UUID issueId) {
        Finding finding =
                new Finding(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "java:S1234",
                        Severity.MAJOR,
                        "Do not do that",
                        "src/main/java/Foo.java",
                        null,
                        10,
                        10,
                        0,
                        0,
                        null,
                        null,
                        null,
                        "weak-fp",
                        NOW);
        finding.assignToIssue(issueId, MatchRung.NEW);
        return finding;
    }

    private static TriageEvent triageEventOn(UUID issueId) {
        return new TriageEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                issueId,
                1,
                "younes",
                IssueStatus.OPEN,
                IssueStatus.RESOLVED_WONT_FIX,
                "accepted risk",
                NOW,
                HashChain.GENESIS_HASH,
                "somehash");
    }
}
