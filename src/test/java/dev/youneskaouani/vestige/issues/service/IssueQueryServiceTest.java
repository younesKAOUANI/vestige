package dev.youneskaouani.vestige.issues.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.youneskaouani.vestige.common.domain.Severity;
import dev.youneskaouani.vestige.common.error.Problems;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRun;
import dev.youneskaouani.vestige.ingestion.domain.AnalysisRunRepository;
import dev.youneskaouani.vestige.issues.domain.Issue;
import dev.youneskaouani.vestige.issues.domain.IssueRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Exercises the one piece of judgement in this class - resolving {@code since-run} to an instant,
 * or failing with 404 when it does not name a real run - with the actual {@code Specification}
 * composition left to {@code IssueSpecifications}' own javadoc guarantee (null-tolerant {@code and}).
 */
@ExtendWith(MockitoExtension.class)
class IssueQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private AnalysisRunRepository runRepository;

    private IssueQueryService service;

    @BeforeEach
    void setUp() {
        service = new IssueQueryService(issueRepository, runRepository);
    }

    @Test
    @DisplayName("without since-run, searches straight away and never touches the run repository")
    void searchesWithoutTouchingRunRepositoryWhenSinceRunIsAbsent() {
        UUID projectId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 25);
        Page<Issue> expected = new PageImpl<>(List.of(openIssue()));
        when(issueRepository.findAll(any(), eq(pageable))).thenReturn(expected);

        Page<Issue> result = service.search(projectId, null, null, null, null, pageable);

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(runRepository);
    }

    @Test
    @DisplayName("since-run resolves the named run to its createdAt before searching")
    void resolvesSinceRunToItsCreatedAtInstant() {
        UUID projectId = UUID.randomUUID();
        UUID sinceRunId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 25);
        Page<Issue> expected = new PageImpl<>(List.of());
        when(runRepository.findById(sinceRunId)).thenReturn(Optional.of(runCreatedAt(sinceRunId, NOW)));
        when(issueRepository.findAll(any(), eq(pageable))).thenReturn(expected);

        Page<Issue> result = service.search(projectId, null, null, null, sinceRunId, pageable);

        assertThat(result).isSameAs(expected);
        verify(runRepository).findById(sinceRunId);
        verify(issueRepository).findAll(any(), eq(pageable));
    }

    @Test
    @DisplayName("a since-run id that does not resolve to a real run is a 404, not a silently-ignored filter")
    void aMissingSinceRunIsNotFound() {
        UUID projectId = UUID.randomUUID();
        UUID sinceRunId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 25);
        when(runRepository.findById(sinceRunId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(projectId, null, null, null, sinceRunId, pageable))
                .isInstanceOf(Problems.NotFound.class);
        verify(issueRepository, never()).findAll(any(), eq(pageable));
    }

    private static Issue openIssue() {
        return new Issue(
                UUID.randomUUID(),
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

    private static AnalysisRun runCreatedAt(UUID id, Instant createdAt) {
        return new AnalysisRun(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "commit123",
                null,
                "ESLint",
                "8.0.0",
                "digest",
                "key",
                createdAt);
    }
}
