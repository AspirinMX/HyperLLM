package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.BindingStatus;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.repository.GatewayRouteRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static app.pagefault.hyperllm.domain.jooq.Tables.CHANNELS;
import static app.pagefault.hyperllm.domain.jooq.Tables.MODEL_CHANNEL_BINDINGS;
import static app.pagefault.hyperllm.domain.jooq.Tables.PROVIDERS;
import static app.pagefault.hyperllm.domain.jooq.Tables.VIRTUAL_MODELS;

@Repository
@RequiredArgsConstructor
public class JooqGatewayRouteRepository implements GatewayRouteRepository {

    private final DSLContext dsl;
    private final JooqVirtualModelRepository virtualModelRepository;
    private final JooqPricingRuleRepository pricingRuleRepository;

    @Override
    public Optional<GatewayModelRoute> findActiveRouteByModelCode(String modelCode, OffsetDateTime at) {
        Optional<VirtualModel> modelOpt = dsl.selectFrom(VIRTUAL_MODELS)
                .where(VIRTUAL_MODELS.MODEL_CODE.eq(modelCode))
                .and(VIRTUAL_MODELS.DELETED_AT.isNull())
                .and(VIRTUAL_MODELS.STATUS.ne(ModelStatus.DISABLED.name()))
                .fetchOptional(virtualModelRepository::toDomain);

        if (modelOpt.isEmpty()) {
            return Optional.empty();
        }
        VirtualModel model = modelOpt.get();
        PricingRule activePricing = pricingRuleRepository.findEffectiveAt(model.id(), at).orElse(null);

        var resolvedBaseUrl = DSL.coalesce(CHANNELS.BASE_URL_OVERRIDE, PROVIDERS.DEFAULT_BASE_URL)
                .as("resolved_base_url");
        var effectiveCostDiscount = DSL.coalesce(
                        MODEL_CHANNEL_BINDINGS.COST_DISCOUNT_BPS_OVERRIDE,
                        CHANNELS.DEFAULT_COST_DISCOUNT_BPS)
                .as("effective_cost_discount_bps");

        List<GatewayChannelCandidate> candidates = dsl.select(
                        MODEL_CHANNEL_BINDINGS.ID,
                        CHANNELS.ID,
                        CHANNELS.NAME,
                        CHANNELS.REGION,
                        PROVIDERS.CODE,
                        PROVIDERS.PROTOCOL_FAMILY,
                        PROVIDERS.SUPPORTED_PROTOCOLS,
                        PROVIDERS.DEFAULT_WIRE_PROTOCOL,
                        MODEL_CHANNEL_BINDINGS.UPSTREAM_PROTOCOL,
                        PROVIDERS.AUTH_SCHEME,
                        resolvedBaseUrl,
                        MODEL_CHANNEL_BINDINGS.UPSTREAM_MODEL_NAME,
                        MODEL_CHANNEL_BINDINGS.PRIORITY_TIER,
                        MODEL_CHANNEL_BINDINGS.WEIGHT,
                        effectiveCostDiscount,
                        CHANNELS.ENCRYPTED_CREDENTIALS,
                        CHANNELS.CREDENTIAL_KEY_VERSION,
                        MODEL_CHANNEL_BINDINGS.EXTRA_HEADERS_JSON,
                        CHANNELS.RPM_LIMIT,
                        CHANNELS.TPM_LIMIT,
                        CHANNELS.MAX_CONCURRENCY
                )
                .from(MODEL_CHANNEL_BINDINGS)
                .join(CHANNELS).on(CHANNELS.ID.eq(MODEL_CHANNEL_BINDINGS.CHANNEL_ID))
                .join(PROVIDERS).on(PROVIDERS.ID.eq(CHANNELS.PROVIDER_ID))
                .where(MODEL_CHANNEL_BINDINGS.VIRTUAL_MODEL_ID.eq(model.id()))
                .and(MODEL_CHANNEL_BINDINGS.DELETED_AT.isNull())
                .and(MODEL_CHANNEL_BINDINGS.STATUS.eq(BindingStatus.ACTIVE.name()))
                .and(CHANNELS.DELETED_AT.isNull())
                .and(CHANNELS.STATUS.eq(ChannelStatus.ACTIVE.name()))
                .and(PROVIDERS.STATUS.eq("ACTIVE"))
                .orderBy(MODEL_CHANNEL_BINDINGS.PRIORITY_TIER.asc(), MODEL_CHANNEL_BINDINGS.WEIGHT.desc())
                .fetch(r -> toCandidate(r, resolvedBaseUrl.getName(), effectiveCostDiscount.getName()));

        return Optional.of(new GatewayModelRoute(model, activePricing, candidates));
    }

    private GatewayChannelCandidate toCandidate(Record r, String baseUrlAlias, String costDiscountAlias) {
        return new GatewayChannelCandidate(
                r.get(MODEL_CHANNEL_BINDINGS.ID),
                r.get(CHANNELS.ID),
                r.get(CHANNELS.NAME),
                r.get(CHANNELS.REGION),
                r.get(PROVIDERS.CODE),
                PgJooqSupport.toEnum(ProtocolFamily.class, r.get(PROVIDERS.PROTOCOL_FAMILY)),
                PgJooqSupport.fromTextArray(r.get(PROVIDERS.SUPPORTED_PROTOCOLS)),
                r.get(PROVIDERS.DEFAULT_WIRE_PROTOCOL),
                r.get(MODEL_CHANNEL_BINDINGS.UPSTREAM_PROTOCOL),
                PgJooqSupport.toEnum(AuthScheme.class, r.get(PROVIDERS.AUTH_SCHEME)),
                r.get(baseUrlAlias, String.class),
                r.get(MODEL_CHANNEL_BINDINGS.UPSTREAM_MODEL_NAME),
                r.get(MODEL_CHANNEL_BINDINGS.PRIORITY_TIER),
                r.get(MODEL_CHANNEL_BINDINGS.WEIGHT),
                r.get(costDiscountAlias, Integer.class),
                r.get(CHANNELS.ENCRYPTED_CREDENTIALS),
                r.get(CHANNELS.CREDENTIAL_KEY_VERSION),
                PgJooqSupport.fromJsonb(r.get(MODEL_CHANNEL_BINDINGS.EXTRA_HEADERS_JSON)),
                r.get(CHANNELS.RPM_LIMIT),
                r.get(CHANNELS.TPM_LIMIT),
                r.get(CHANNELS.MAX_CONCURRENCY)
        );
    }
}
