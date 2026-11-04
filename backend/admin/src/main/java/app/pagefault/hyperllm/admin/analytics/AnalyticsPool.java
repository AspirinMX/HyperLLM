package app.pagefault.hyperllm.admin.analytics;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Connection pool of the report warehouse.
 *
 * <p>Deliberately not a {@code DataSource} bean: Spring Boot would then treat it as the application
 * datasource and Flyway, jOOQ and the ledger would talk to ClickHouse. The pool is reached through
 * this component only, which also keeps warehouse connections out of the ledger pool.
 */
@Component
public class AnalyticsPool {

    private final HikariDataSource dataSource;

    public AnalyticsPool(AdminProperties properties) {
        AdminProperties.ClickHouse clickhouse = properties.getAnalytics().getClickhouse();
        HikariConfig config = new HikariConfig();
        config.setPoolName("clickhouse-analytics");
        config.setJdbcUrl(clickhouse.getUrl());
        config.setUsername(clickhouse.getUsername());
        config.setPassword(clickhouse.getPassword());
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        config.setMaximumPoolSize(clickhouse.getPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(clickhouse.getConnectTimeoutMs());
        // The warehouse may start after admin; the ingestion listener retries until it is there.
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
