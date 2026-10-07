package app.pagefault.hyperllm.domain.model.routing;

import java.util.List;

/**
 * Hydrated virtual model, active pricing snapshot, and candidate upstream channels.
 */
public record GatewayModelRoute(
        VirtualModel model,
        PricingRule activePricing,
        List<GatewayChannelCandidate> candidates
) {
}
