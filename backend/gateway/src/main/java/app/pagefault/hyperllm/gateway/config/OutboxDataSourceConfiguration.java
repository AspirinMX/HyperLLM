package app.pagefault.hyperllm.gateway.config;

import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.domain.repository.jooq.JooqOutboxEventRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Isolates settlement appends from cold gateway database reads. */
@Configuration(proxyBeanMethods = false)
class OutboxDataSourceConfiguration {

    @Bean(defaultCandidate = false, destroyMethod = "close")
    HikariDataSource gatewayOutboxDataSource(JdbcConnectionDetails connectionDetails) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("gateway-outbox");
        pool.setJdbcUrl(connectionDetails.getJdbcUrl());
        pool.setUsername(connectionDetails.getUsername());
        pool.setPassword(connectionDetails.getPassword());
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(2_000L);
        pool.setInitializationFailTimeout(-1L);
        return pool;
    }

    @Bean(defaultCandidate = false)
    OutboxEventRepository gatewayOutboxRepository(
            @Qualifier("gatewayOutboxDataSource") HikariDataSource dataSource
    ) {
        return new JooqOutboxEventRepository(DSL.using(dataSource, SQLDialect.POSTGRES));
    }
}
