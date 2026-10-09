package app.pagefault.hyperllm.domain;

import app.pagefault.hyperllm.common.id.UuidV7;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V3 to V4 upgrade path on a dedicated database inside the shared container.
 * Deliberately bypasses Spring: the point is to exercise Flyway plus real PostgreSQL partitions,
 * which no repository test can observe.
 */
@Tag("integration")
class V4MigrationPathIT {

    private static final String PATH_DB = "hyperllm_v4_path";
    private static final PostgreSQLContainer<?> PG = AbstractPostgresIntegrationTest.POSTGRES;

    @BeforeAll
    static void createDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + PATH_DB);
            st.execute("CREATE DATABASE " + PATH_DB);
        }
    }

    @Test
    void upgradesV3LedgerDataToV4WithoutRewritingIt() throws Exception {
        migrateTo("3");

        assertThat(tableExists("payment_orders"))
                .as("V4 tables must not exist while pinned to V3")
                .isFalse();
        assertThat(partitions("wallet_ledger"))
                .as("V3 pre-creates 14 monthly partitions (-1..+12)")
                .hasSize(14);

        UUID orgId = seedV3Data();
        UUID ledgerId = UuidV7.next();
        UUID auditId = UuidV7.next();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

        try (Connection conn = open()) {
            exec(conn, "INSERT INTO wallet_ledger (id, org_id, ledger_type, idempotency_key, cash_delta_micros, created_at)"
                    + " VALUES (?, ?, 'CONSUME', ?, -1234, ?)", ledgerId, orgId, "req:v3-row", Timestamp.from(createdAt.toInstant()));
            exec(conn, "INSERT INTO audit_logs (id, actor_type, action, resource_type, created_at)"
                    + " VALUES (?, 'SYSTEM', 'SEED', 'wallet_ledger', ?)", auditId, Timestamp.from(createdAt.toInstant()));
        }
        long ledgerBefore = countRows("wallet_ledger");
        long auditBefore = countRows("audit_logs");

        migrateTo(null);

        assertThat(tableExists("payment_orders")).isTrue();
        assertThat(tableExists("outbox_events")).isTrue();
        assertThat(partitions("wallet_ledger"))
                .as("ALTER TABLE must not drop or recreate the V3 partitions")
                .hasSize(14);
        assertThat(countRows("wallet_ledger")).isEqualTo(ledgerBefore);
        assertThat(countRows("audit_logs")).isEqualTo(auditBefore);

        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT cash_delta_micros, ref_order_id FROM wallet_ledger WHERE id = ?")) {
                ps.setObject(1, ledgerId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(-1234L);
                    assertThat(rs.getObject(2)).isNull();
                }
            }
        }

        assertThat(fkConstraintCount()).as("FK must be created on the parent").isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> {
            try (Connection conn = open()) {
                exec(conn, "INSERT INTO wallet_ledger (id, org_id, ledger_type, idempotency_key, cash_delta_micros, ref_order_id, created_at)"
                                + " VALUES (?, ?, 'CONSUME', 'req:bad-fk', -1, ?, now())",
                        UuidV7.next(), orgId, UUID.randomUUID());
            }
        }).isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("23503"));

        try (Connection conn = open()) {
            exec(conn, "INSERT INTO wallet_ledger (id, org_id, ledger_type, idempotency_key, cash_delta_micros, created_at)"
                    + " VALUES (?, ?, 'CONSUME', 'req:null-fk', -1, now())", UuidV7.next(), orgId);
        }
        assertThat(countRows("wallet_ledger")).isEqualTo(ledgerBefore + 1);
    }

    private static UUID seedV3Data() throws SQLException {
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try (Connection conn = open()) {
            exec(conn, "INSERT INTO users (id, email, display_name, status, version, created_at, updated_at)"
                            + " VALUES (?, ?, 'V4 Path User', 'ACTIVE', 0, ?, ?)",
                    ownerId, "v4-path-" + ownerId + "@acme.dev", Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
            exec(conn, "INSERT INTO organizations (id, name, slug, owner_user_id, tier_id, is_personal, status, version, created_at, updated_at)"
                            + " VALUES (?, 'V4 Path Org', ?, ?, (SELECT id FROM customer_tiers WHERE tier_code = 'STANDARD'), false, 'ACTIVE', 0, ?, ?)",
                    orgId, "v4-path-" + orgId, ownerId,
                    Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
        }
        return orgId;
    }

    private static void migrateTo(String version) {
        var configuration = Flyway.configure()
                .dataSource(pathUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration");
        if (version != null) {
            configuration.target(MigrationVersion.fromVersion(version));
        }
        configuration.load().migrate();
    }

    private static String pathUrl() {
        String url = PG.getJdbcUrl();
        return url.substring(0, url.lastIndexOf('/') + 1) + PATH_DB;
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(pathUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private static boolean tableExists(String table) throws SQLException {
        return count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '" + table + "'") > 0;
    }

    private static List<String> partitions(String parent) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT c.relname FROM pg_class c JOIN pg_inherits i ON i.inhrelid = c.oid"
                             + " JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = ? ORDER BY c.relname")) {
            ps.setString(1, parent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static long fkConstraintCount() throws SQLException {
        return count("SELECT count(*) FROM pg_constraint WHERE conname = 'fk_wallet_ledger_ref_order'");
    }

    private static long countRows(String table) throws SQLException {
        return count("SELECT count(*) FROM " + table);
    }

    private static long count(String sql) throws SQLException {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
