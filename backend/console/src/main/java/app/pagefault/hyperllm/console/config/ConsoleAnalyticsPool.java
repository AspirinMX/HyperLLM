package app.pagefault.hyperllm.console.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Read-only warehouse pool for the usage page.
 *
 * <p>Not a {@code DataSource} bean: that would become the application datasource and Flyway, jOOQ
 * and the tenant queries would run against ClickHouse. The pool is reached through this component,
 * which also keeps a slow report from consuming the tenant database connections.
 */
@Component
public class ConsoleAnalyticsPool {

    private final HikariDataSource dataSource;

    public ConsoleAnalyticsPool(ConsoleProperties properties) {
        ConsoleProperties.Analytics analytics = properties.getAnalytics();
        HikariConfig config = new HikariConfig();
        config.setPoolName("clickhouse-console");
        config.setJdbcUrl(analytics.getUrl());
        config.setUsername(analytics.getUsername());
        config.setPassword(analytics.getPassword());
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        config.setMaximumPoolSize(analytics.getPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(analytics.getConnectTimeoutMs());
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @PreDestroy
    public void close() {
        dataSource.close();
    }
}
