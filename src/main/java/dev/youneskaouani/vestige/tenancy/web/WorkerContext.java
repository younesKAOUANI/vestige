package dev.youneskaouani.vestige.tenancy.web;

/**
 * Marks the current thread as the outbox worker, acting across every tenant rather than one.
 *
 * <p>The worker has to find the next runnable job before it can know which organisation it belongs
 * to (§4.2), so it cannot simply set {@link TenantContext} the way a request does. Instead it sets
 * this flag for exactly the query that claims a job; {@link
 * dev.youneskaouani.vestige.tenancy.config.TenantRoutingDataSource} publishes it into the database
 * session as {@code vestige.worker = on}, which the {@code analysis_job}/{@code poison_report} row
 * level security policies treat as a second, explicit way in (V2 migration). Every other table's
 * policy does not check it at all, so this flag grants no access whatsoever to tenant data - only
 * to the queue.
 *
 * <p>As soon as a job is claimed, the worker learns which organisation it belongs to, clears this
 * flag, and sets {@link TenantContext} instead for the rest of that job's processing. The two
 * contexts are therefore never both active for tenant-data writes.
 */
public final class WorkerContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private WorkerContext() {}

    public static void activate() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void clear() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** Runs {@code action} with the worker escalation active, always clearing it afterwards. */
    public static <T> T runEscalated(java.util.function.Supplier<T> action) {
        activate();
        try {
            return action.get();
        } finally {
            clear();
        }
    }
}
