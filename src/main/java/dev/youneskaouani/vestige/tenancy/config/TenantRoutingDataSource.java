package dev.youneskaouani.vestige.tenancy.config;

import dev.youneskaouani.vestige.tenancy.web.TenantContext;
import dev.youneskaouani.vestige.tenancy.web.WorkerContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Publishes the current tenant into the database session at the moment a connection is handed to
 * a transaction, which is what the row-level security policies in V2 read (§5.2).
 *
 * <p><b>Why {@link AbstractRoutingDataSource} for a single physical database.</b> Its usual job is
 * choosing between several target data sources; v1 has exactly one, so
 * {@link #determineCurrentLookupKey()} always resolves to the same key and the "routing" is
 * degenerate on purpose. It is still the right base class, because the property that actually
 * matters here is a different one: it is the one standard Spring hook that runs on every
 * connection checkout, before anything else touches that connection. An AOP aspect around
 * {@code @Transactional} was the obvious alternative and does not work cleanly - Spring's
 * transaction advisor sits at {@code LOWEST_PRECEDENCE}, so an ordinary aspect runs
 * <em>outside</em> the transaction it is trying to configure, and connections are frequently
 * acquired lazily, on the first statement, not at {@code doBegin}. Overriding
 * {@link #getConnection()} sidesteps both problems: whatever acquires the JDBC connection - JPA's
 * lazy acquisition, plain JDBC, a health check - gets the tenant set first. If Vestige grows a
 * second physical database (a read replica, a per-region shard), the target map here is exactly
 * where that would plug in, which is the other reason this is the right class rather than a
 * decorator invented for this one purpose.
 *
 * <p>{@code set_config(..., true)} rather than {@code SET LOCAL}: PostgreSQL's {@code SET} does
 * not take bind parameters, and building the statement by string concatenation around a value
 * that arrives with the request is how one writes an injection. The trailing {@code true} makes it
 * {@code LOCAL} in effect - scoped to the current transaction - which matters because HikariCP
 * reuses physical connections across unrelated transactions; a session-scoped value would leak the
 * previous tenant into whichever request happens to reuse the connection next.
 */
public final class TenantRoutingDataSource extends AbstractRoutingDataSource {

    /** The only routing key that exists in v1 - see class javadoc. */
    private static final String PRIMARY = "primary";

    private static final String PUBLISH_CONTEXT_SQL =
            "select set_config('vestige.current_org', ?, true), set_config('vestige.worker', ?, true)";

    public TenantRoutingDataSource(DataSource physical) {
        setTargetDataSources(Map.of(PRIMARY, physical));
        setDefaultTargetDataSource(physical);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return PRIMARY;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        publishContext(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = super.getConnection(username, password);
        publishContext(connection);
        return connection;
    }

    /**
     * An absent tenant is published as the empty string, which the policies turn into NULL and
     * therefore into "no rows" (V2's {@code nullif(..., '')}). Failing closed is the whole point.
     */
    private void publishContext(Connection connection) throws SQLException {
        String organizationId = TenantContext.current().map(UUID::toString).orElse("");
        String worker = WorkerContext.isActive() ? "on" : "off";
        try (PreparedStatement statement = connection.prepareStatement(PUBLISH_CONTEXT_SQL)) {
            statement.setString(1, organizationId);
            statement.setString(2, worker);
            statement.execute();
        }
    }
}
