package app.pagefault.hyperllm.admin.maintenance;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Creates partitions through the same function the migration uses, against a real PostgreSQL. */
@Tag("integration")
class PartitionMaintenanceIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_partition_it")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES);
    }

    @Test
    void createsAPartitionForEveryMonthAhead() {
        OffsetDateTime monthStart = OffsetDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS);
        OffsetDateTime target = monthStart.plusMonths(6);

        new PartitionMaintenanceJob(dsl, 6).ensureFuturePartitions();

        String expected = "wallet_ledger_" + target.format(java.time.format.DateTimeFormatter.ofPattern("yyyy_MM"));
        List<String> tables = dsl.fetch("select tablename from pg_tables where schemaname = 'public'")
                .getValues(0, String.class);
        assertThat(tables).contains(expected);
        assertThat(tables).contains(expected.replace("wallet_ledger", "audit_logs"));
    }

    @Test
    void isIdempotentWhenThePartitionAlreadyExists() {
        PartitionMaintenanceJob job = new PartitionMaintenanceJob(dsl, 2);

        job.ensureFuturePartitions();
        job.ensureFuturePartitions();

        Integer count = dsl.fetchOne(
                "select count(*) from pg_tables where schemaname = 'public' and tablename like 'wallet_ledger_%'")
                .get(0, Integer.class);
        assertThat(count).isPositive();
    }
}
