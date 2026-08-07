package dev.youneskaouani.vestige.tenancy.web;

import dev.youneskaouani.vestige.common.error.Problems;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant the current thread is acting for.
 *
 * <p>A thread-local is the right shape here despite the usual objections: the value is set once at
 * the edge of a request (or at the start of a background job), read by exactly one component - the
 * transaction manager, which issues the {@code SET LOCAL} - and cleared in a {@code finally}. It is
 * never used as an ambient parameter by business logic, which takes the organisation id explicitly.
 *
 * <p>Note what this class is <em>not</em>: it is not the security boundary. Isolation is enforced
 * by the row-level security policies in the database. If this context were wrong, or forgotten
 * entirely, the policies would return zero rows rather than another tenant's data.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID organizationId) {
        CURRENT.set(organizationId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current tenant, or a 401 if there is none. */
    public static UUID require() {
        return current()
                .orElseThrow(
                        () ->
                                new Problems.Unauthorized(
                                        "No authenticated organization on this request"));
    }
}
