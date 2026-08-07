package dev.youneskaouani.vestige.ingestion.domain;

/**
 * Lifecycle of a submitted report (§2.2): {@code RECEIVED -> PARSING -> MATCHING -> COMPLETED},
 * with {@code FAILED} recording a transient per-attempt failure and {@code QUARANTINED} the
 * terminal state once the outbox worker has exhausted its retries (see {@code
 * V1__core_schema.sql}'s comment on {@code analysis_run.status} and {@code AnalysisJobRepository}).
 *
 * <p>{@code PARSING} and {@code MATCHING} are set within the run's single processing transaction
 * (§4.1) and are therefore only ever durably visible to a concurrent reader if the worker crashes
 * before committing - a run that completes normally jumps straight from {@code RECEIVED} to {@code
 * COMPLETED}. They exist as real states anyway, rather than being collapsed into one generic {@code
 * PROCESSING}, because a crash-recovery read (or a support engineer looking at a stuck run)
 * benefits from knowing which half of the transaction was in flight.
 *
 * <p>{@code FAILED} is intentionally not further split from {@code QUARANTINED}: an attempt that
 * fails always leaves the run in {@code FAILED} first, and {@link #QUARANTINED} is a second,
 * explicit transition the worker makes only once {@code analysis_job.attempt_count} has exhausted
 * {@code vestige.worker.max-attempts}. A run can therefore legitimately sit in {@code FAILED}
 * between retries; it is not itself a terminal state.
 *
 * <p>{@link #DUPLICATE} is different from all of these: it is never stored. It is the status a
 * <em>submission</em> is answered with when it collided with a run that already exists, while the
 * run itself keeps whatever status its own processing reached. Saying so explicitly is clearer than
 * answering a duplicate submission with {@code COMPLETED} and leaving the client to work out that
 * nothing happened.
 */
public enum RunStatus {
    RECEIVED,
    PARSING,
    MATCHING,
    COMPLETED,
    FAILED,
    QUARANTINED,
    DUPLICATE
}
