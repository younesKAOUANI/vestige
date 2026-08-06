package dev.youneskaouani.vestige.tenancy.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces Spring Boot's auto-configured connection pool with one wrapped in
 * {@link TenantRoutingDataSource}.
 *
 * <p>{@code spring.datasource.*} still configures the pool itself (Spring Boot's
 * {@link DataSourceProperties} bean is unaffected by this class); what changes is that the bean
 * graph gets a {@link TenantRoutingDataSource} in front of the pool instead of the pool directly.
 * Flyway is untouched by this: {@code spring.flyway.url/user/password} in application.yml give it
 * its own connection, as the database owner, entirely separate from this pool - see V2's migration
 * comment for why the two must not be the same role.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(DataSourceProperties properties) {
        DataSource physical =
                properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        return new TenantRoutingDataSource(physical);
    }
}
