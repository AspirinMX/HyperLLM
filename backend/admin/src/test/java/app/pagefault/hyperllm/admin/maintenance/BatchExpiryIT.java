package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import app.pagefault.hyperllm.domain.repository.jooq.JooqBonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.jooq.JooqOrganizationRepository;
import app.pagefault.hyperllm.domain.repository.jooq.JooqResourcePackRepository;
import app.pagefault.hyperllm.domain.repository.jooq.JooqWalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.jooq.JooqWalletRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Expiry against a real PostgreSQL: what moves, what does not, and what a second run does. */
@Tag("integration")
class BatchExpiryIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_expiry_it")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static {
        POSTGRES.start();
    }

    private DSLContext dsl;
    private WalletRepository walletRepository;
    private WalletLedgerRepository ledgerRepository;
    private BonusGrantRepository grantRepository;
    private ResourcePackRepository packRepository;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

        walletRepository = new JooqWalletRepository(dsl);
        ledgerRepository = new JooqWalletLedgerRepository(dsl);
        grantRepository = new JooqBonusGrantRepository(dsl);
        packRepository = new JooqResourcePackRepository(dsl);
        OrganizationRepository organizationRepository = new JooqOrganizationRepository(dsl);

        UUID ownerId = UuidV7.next();
        orgId = UuidV7.next();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.execute("INSERT INTO users (id, email, display_name, status, default_org_id, created_at, updated_at)"
                + " VALUES (?, ?, 'Owner', 'ACTIVE', NULL, now(), now())", ownerId, ownerId + "@acme.dev");
        UUID tierId = dsl.fetchOne("select id from customer_tiers where tier_code = 'STANDARD'")
                .get(0, UUID.class);
        organizationRepository.insert(new Organization(
                // The whole id: UUIDv7 values generated in the same millisecond share their leading
                // hex digits, so a short prefix is not a usable unique slug.
                orgId, "Expiry Org", "exp-" + orgId.toString().replace("-", ""),
                ownerId, tierId, null, 10, false, OrgStatus.ACTIVE, null, 0L, now, now));
        walletRepository.insert(new Wallet(orgId, 0L, 5_000L, 0L, 5_000L, 0L, 0L, null, 0L, now));
        grantRepository.insert(new BonusGrant(
                UuidV7.next(), orgId, GrantSource.SIGNUP, null, null, null,
                5_000L, 5_000L, now.minusDays(1), EntitlementStatus.ACTIVE, 0L, now, now));
    }

    @Test
    void booksTheRemainderAndClosesTheBatchOnce() {
        BatchExpiryService service = new BatchExpiryService(ledgerRepository, walletRepository, grantRepository);
        BatchExpiryJob job = new BatchExpiryJob(grantRepository, packRepository, service, 100);

        job.sweep();

        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(bonusBalance()).isZero();
        assertThat(grantStatuses()).containsOnly("EXPIRED");
    }

    @Test
    void leavesTheBalanceAloneOnASecondRun() {
        BatchExpiryService service = new BatchExpiryService(ledgerRepository, walletRepository, grantRepository);
        BatchExpiryJob job = new BatchExpiryJob(grantRepository, packRepository, service, 100);

        job.sweep();
        job.sweep();

        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(bonusBalance()).isZero();
    }

    private int ledgerRows() {
        return dsl.fetchOne("select count(*) from wallet_ledger where org_id = ?", orgId)
                .get(0, Integer.class);
    }

    private long bonusBalance() {
        return dsl.fetchOne("select bonus_balance_micros from wallets where org_id = ?", orgId)
                .get(0, Long.class);
    }

    private java.util.List<String> grantStatuses() {
        return dsl.fetch("select status from bonus_grants where org_id = ?", orgId)
                .getValues(0, String.class);
    }
}
