package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.CustomerTiersRecord;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.CUSTOMER_TIERS;

@Repository
@RequiredArgsConstructor
public class JooqCustomerTierRepository implements CustomerTierRepository {

    private final DSLContext dsl;

    @Override
    public Optional<CustomerTier> findById(UUID id) {
        return dsl.selectFrom(CUSTOMER_TIERS)
                .where(CUSTOMER_TIERS.ID.eq(id))
                .and(CUSTOMER_TIERS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<CustomerTier> findActiveByCode(String tierCode) {
        return dsl.selectFrom(CUSTOMER_TIERS)
                .where(CUSTOMER_TIERS.TIER_CODE.eq(tierCode))
                .and(CUSTOMER_TIERS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<CustomerTier> listAllActive() {
        return dsl.selectFrom(CUSTOMER_TIERS)
                .where(CUSTOMER_TIERS.DELETED_AT.isNull())
                .orderBy(CUSTOMER_TIERS.DEFAULT_DISCOUNT_BPS.desc(), CUSTOMER_TIERS.TIER_CODE.asc())
                .fetch(this::toDomain);
    }

    private CustomerTier toDomain(CustomerTiersRecord r) {
        return new CustomerTier(
                r.getId(),
                r.getTierCode(),
                r.getDisplayName(),
                r.getDefaultDiscountBps(),
                r.getDefaultOrgConcurrency(),
                r.getDescription(),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
