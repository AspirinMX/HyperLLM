package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.PricingRulesRecord;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.repository.PricingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PRICING_RULES;

@Repository
@RequiredArgsConstructor
public class JooqPricingRuleRepository implements PricingRuleRepository {

    private final DSLContext dsl;

    @Override
    public boolean hasOverlappingWindow(UUID virtualModelId, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {
        Condition endAfterNewStart = PRICING_RULES.EFFECTIVE_TO.isNull()
                .or(PRICING_RULES.EFFECTIVE_TO.gt(effectiveFrom));
        Condition query = PRICING_RULES.VIRTUAL_MODEL_ID.eq(virtualModelId).and(endAfterNewStart);
        if (effectiveTo != null) {
            query = query.and(PRICING_RULES.EFFECTIVE_FROM.lt(effectiveTo));
        }
        return dsl.fetchExists(dsl.selectOne().from(PRICING_RULES).where(query));
    }

    @Override
    public void insert(PricingRule rule) {
        dsl.insertInto(PRICING_RULES)
                .set(PRICING_RULES.ID, rule.id())
                .set(PRICING_RULES.VIRTUAL_MODEL_ID, rule.virtualModelId())
                .set(PRICING_RULES.BILLING_MODE, rule.billingMode().name())
                .set(PRICING_RULES.INPUT_PRICE_MICROS_1M, rule.inputPriceMicros1m())
                .set(PRICING_RULES.OUTPUT_PRICE_MICROS_1M, rule.outputPriceMicros1m())
                .set(PRICING_RULES.CACHE_READ_PRICE_MICROS_1M, rule.cacheReadPriceMicros1m())
                .set(PRICING_RULES.CACHE_WRITE_PRICE_MICROS_1M, rule.cacheWritePriceMicros1m())
                .set(PRICING_RULES.REASONING_PRICE_MICROS_1M, rule.reasoningPriceMicros1m())
                .set(PRICING_RULES.PER_CALL_MICROS, rule.perCallMicros())
                .set(PRICING_RULES.TIERED_RULES_JSON, PgJooqSupport.toJsonb(rule.tieredRulesJson()))
                .set(PRICING_RULES.MODALITY_RULES_JSON, PgJooqSupport.toJsonb(rule.modalityRulesJson()))
                .set(PRICING_RULES.EFFECTIVE_FROM, rule.effectiveFrom())
                .set(PRICING_RULES.EFFECTIVE_TO, rule.effectiveTo())
                .set(PRICING_RULES.CREATED_BY_ADMIN_ID, rule.createdByAdminId())
                .set(PRICING_RULES.CREATED_AT, rule.createdAt())
                .execute();
    }

    @Override
    public Optional<PricingRule> findEffectiveAt(UUID virtualModelId, OffsetDateTime timestamp) {
        return dsl.selectFrom(PRICING_RULES)
                .where(PRICING_RULES.VIRTUAL_MODEL_ID.eq(virtualModelId))
                .and(PRICING_RULES.EFFECTIVE_FROM.le(timestamp))
                .and(PRICING_RULES.EFFECTIVE_TO.isNull().or(PRICING_RULES.EFFECTIVE_TO.gt(timestamp)))
                .orderBy(PRICING_RULES.EFFECTIVE_FROM.desc())
                .limit(1)
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<PricingRule> listByModelId(UUID virtualModelId) {
        return dsl.selectFrom(PRICING_RULES)
                .where(PRICING_RULES.VIRTUAL_MODEL_ID.eq(virtualModelId))
                .orderBy(PRICING_RULES.EFFECTIVE_FROM.desc())
                .fetch(this::toDomain);
    }

    PricingRule toDomain(PricingRulesRecord r) {
        return new PricingRule(
                r.getId(),
                r.getVirtualModelId(),
                PgJooqSupport.toEnum(BillingMode.class, r.getBillingMode()),
                r.getInputPriceMicros_1m(),
                r.getOutputPriceMicros_1m(),
                r.getCacheReadPriceMicros_1m(),
                r.getCacheWritePriceMicros_1m(),
                r.getReasoningPriceMicros_1m(),
                r.getPerCallMicros(),
                PgJooqSupport.fromJsonb(r.getTieredRulesJson()),
                PgJooqSupport.fromJsonb(r.getModalityRulesJson()),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                r.getCreatedByAdminId(),
                r.getCreatedAt()
        );
    }
}
