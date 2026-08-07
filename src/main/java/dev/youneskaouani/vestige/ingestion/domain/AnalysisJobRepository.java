package dev.youneskaouani.vestige.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    /**
     * Claims the next runnable job for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes several workers safe without a coordinator: a
     * row another worker is already holding is stepped over rather than waited on, so throughput
     * scales with the number of workers instead of serialising on the head of the queue.
     *
     * <p>A row whose lease has expired is claimable again, which is how a worker that was killed
     * mid-job releases its work.
     */
    @Query(
            value =
                    """
                    select id
                    from analysis_job
                    where next_attempt_at <= :now
                      and (status = 'PENDING' or (status = 'RUNNING' and locked_until < :now))
                    order by next_attempt_at
                    limit 1
                    for update skip locked
                    """,
            nativeQuery = true)
    List<UUID> claimNextRunnable(@Param("now") Instant now);

    Optional<AnalysisJob> findByAnalysisRunId(UUID analysisRunId);

    List<AnalysisJob> findAllByStatus(AnalysisJob.JobStatus status);
}
