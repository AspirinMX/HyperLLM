package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.domain.pricing.PricingSource;
import app.pagefault.hyperllm.domain.pricing.ResolvedPricing;

/**
 * Serialises {@code allocation_detail_json} v1.
 *
 * <p>Hand-written rather than reflective so field order and shape stay stable for downstream
 * consumers and golden-file tests. The detail is self-contained: provenance, usage, every leg and
 * the totals, so a bill can be explained without joining any table.
 */
public final class AllocationDetailCodec {

    /** Guards the Kafka message and JSONB column against unbounded batch fan-out. */
    public static final int MAX_LEGS = 32;

    private AllocationDetailCodec() {
    }

    public static String encodeV1(ResolvedPricing pricing, BillableUsage usage, AllocationPlan plan) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"v\":1,\"pricing\":{");
        json.append("\"source\":\"").append(pricing.source().name()).append("\",");
        json.append("\"billingMode\":\"").append(pricing.billingMode().name()).append("\",");
        json.append("\"discountBps\":").append(pricing.discountBps()).append(',');
        json.append("\"tierIndex\":").append(pricing.tierIndex() == null ? "null" : pricing.tierIndex()).append(',');
        json.append("\"overrideId\":").append(quoted(pricing.overrideId() == null ? null : pricing.overrideId().toString())).append(',');
        json.append("\"unitPrices\":{");
        json.append("\"input\":").append(pricing.inputPriceMicros1m()).append(',');
        json.append("\"output\":").append(pricing.outputPriceMicros1m()).append(',');
        json.append("\"cacheRead\":").append(pricing.cacheReadPriceMicros1m()).append(',');
        json.append("\"cacheWrite\":").append(pricing.cacheWritePriceMicros1m()).append(',');
        json.append("\"reasoning\":").append(pricing.reasoningPriceMicros1m());
        json.append("}},");

        json.append("\"usage\":{");
        json.append("\"prompt\":").append(usage.promptTokens()).append(',');
        json.append("\"completion\":").append(usage.completionTokens()).append(',');
        json.append("\"cacheRead\":").append(usage.cacheReadTokens()).append(',');
        json.append("\"cacheWrite\":").append(usage.cacheWriteTokens()).append(',');
        json.append("\"reasoning\":").append(usage.reasoningTokens());
        json.append("},");

        int emitted = Math.min(plan.legs().size(), MAX_LEGS);
        json.append("\"legs\":[");
        for (int i = 0; i < emitted; i++) {
            AllocationLeg leg = plan.legs().get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"type\":\"").append(leg.type().name()).append('"');
            if (leg.id() != null) {
                json.append(",\"id\":\"").append(leg.id()).append('"');
            }
            if (leg.units() > 0L) {
                json.append(",\"units\":").append(leg.units());
            }
            json.append(",\"micros\":").append(leg.micros()).append('}');
        }
        json.append("],");

        json.append("\"totals\":{");
        json.append("\"chargedMicros\":").append(plan.chargedMicros()).append(',');
        json.append("\"shortfallMicros\":").append(plan.shortfallMicros()).append(',');
        json.append("\"degraded\":").append(plan.degraded()).append(',');
        json.append("\"truncatedLegs\":").append(plan.legs().size() - emitted);
        json.append("}}");
        return json.toString();
    }

    /** Exposed for callers that only need the provenance half, such as pre-hold diagnostics. */
    public static String describeSource(ResolvedPricing pricing) {
        return pricing.source() == PricingSource.OVERRIDE_FIXED
                ? "override-fixed"
                : pricing.source().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
