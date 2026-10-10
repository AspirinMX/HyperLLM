package app.pagefault.hyperllm.domain.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Contract v1 of {@code pricing_rules.tiered_rules_json}: a list of tiers, each replacing all five
 * unit prices for requests whose input-side token count falls into {@code [minInputTokens, max]}.
 *
 * <p>Selection uses input tokens only, because a pre-hold has to pick a tier before the completion
 * exists. Reading is deliberately lenient (a malformed document falls back to the base prices);
 * writing is validated by {@link #validate(String)} when a pricing rule is published.
 */
public final class TieredPricingRules {

    public static final int MAX_TIERS = 8;
    public static final long MAX_PRICE_MICROS_1M = 2_000_000_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TieredPricingRules() {
    }

    public record Tier(
            long minInputTokens,
            Long maxInputTokens,
            long inputPriceMicros1m,
            long outputPriceMicros1m,
            long cacheReadPriceMicros1m,
            long cacheWritePriceMicros1m,
            long reasoningPriceMicros1m
    ) {
    }

    /** Parses usable tiers, skipping entries that are structurally incomplete. Never throws. */
    public static List<Tier> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<Tier> tiers = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                if (!node.hasNonNull("minInputTokens")) {
                    continue;
                }
                tiers.add(new Tier(
                        node.get("minInputTokens").asLong(),
                        node.hasNonNull("maxInputTokens") ? node.get("maxInputTokens").asLong() : null,
                        longField(node, "inputPriceMicros1m"),
                        longField(node, "outputPriceMicros1m"),
                        longField(node, "cacheReadPriceMicros1m"),
                        longField(node, "cacheWritePriceMicros1m"),
                        longField(node, "reasoningPriceMicros1m")
                ));
            }
            return tiers;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** Returns the tier covering the given input token count, if the document defines one. */
    public static Optional<Tier> select(List<Tier> tiers, long inputTokens) {
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            if (inputTokens >= tier.minInputTokens()
                    && (tier.maxInputTokens() == null || inputTokens <= tier.maxInputTokens())) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /** Publish-time validation. An empty list is legal and simply means "no tiering". */
    public static List<String> validate(String json) {
        List<String> errors = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return errors;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception ex) {
            return List.of("tiered_rules_json is not valid JSON");
        }
        if (root == null || !root.isArray()) {
            return List.of("tiered_rules_json must be a JSON array");
        }
        if (root.size() > MAX_TIERS) {
            errors.add("at most " + MAX_TIERS + " tiers are allowed, found " + root.size());
        }
        Long previousMax = null;
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            String prefix = "tier[" + i + "]: ";
            if (!node.hasNonNull("minInputTokens")) {
                errors.add(prefix + "minInputTokens is required");
                continue;
            }
            long min = node.get("minInputTokens").asLong();
            Long max = node.hasNonNull("maxInputTokens") ? node.get("maxInputTokens").asLong() : null;

            if (i == 0 && min != 0L) {
                errors.add(prefix + "the first tier must start at 0");
            }
            if (i > 0 && previousMax != null && min != previousMax + 1) {
                errors.add(prefix + "must start exactly after the previous tier's max");
            }
            if (max != null && max < min) {
                errors.add(prefix + "maxInputTokens must not be smaller than minInputTokens");
            }
            if (max == null && i != root.size() - 1) {
                errors.add(prefix + "only the last tier may omit maxInputTokens");
            }
            checkPrice(errors, node, prefix, "inputPriceMicros1m");
            checkPrice(errors, node, prefix, "outputPriceMicros1m");
            checkPrice(errors, node, prefix, "cacheReadPriceMicros1m");
            checkPrice(errors, node, prefix, "cacheWritePriceMicros1m");
            checkPrice(errors, node, prefix, "reasoningPriceMicros1m");
            previousMax = max;
        }
        return errors;
    }

    private static void checkPrice(List<String> errors, JsonNode node, String prefix, String field) {
        if (!node.hasNonNull(field)) {
            errors.add(prefix + field + " is required");
            return;
        }
        long value = node.get(field).asLong();
        if (value < 0 || value > MAX_PRICE_MICROS_1M) {
            errors.add(prefix + field + " must be between 0 and " + MAX_PRICE_MICROS_1M);
        }
    }

    private static long longField(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : 0L;
    }
}
