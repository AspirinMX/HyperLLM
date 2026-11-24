package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Selects an upstream channel candidate using ascending {@code priorityTier},
 * homomorphic-first preference within each tier, and weighted random selection.
 */
@Component
public class ChannelSelector {

    public GatewayChannelCandidate selectNext(
            List<GatewayChannelCandidate> candidates,
            Set<UUID> excludedChannelIds
    ) {
        return selectNext(candidates, excludedChannelIds, null);
    }

    /**
     * Picks the next candidate:
     * <ol>
     *   <li>Lowest {@code priorityTier} that still has at least one non-excluded candidate.</li>
     *   <li>Within that tier, strictly prefers homomorphic candidates (supporting {@code inboundProtocol}
     *       for zero-overhead passthrough) over heterogeneous translation candidates.</li>
     *   <li>Within the chosen sub-bucket, selects via weighted random sampling.</li>
     * </ol>
     */
    public GatewayChannelCandidate selectNext(
            List<GatewayChannelCandidate> candidates,
            Set<UUID> excludedChannelIds,
            ProtocolKind inboundProtocol
    ) {
        List<GatewayChannelCandidate> tierEligible = new ArrayList<>();
        short lowestTier = Short.MAX_VALUE;
        for (GatewayChannelCandidate c : candidates) {
            if (excludedChannelIds != null && excludedChannelIds.contains(c.channelId())) {
                continue;
            }
            if (c.priorityTier() < lowestTier) {
                lowestTier = c.priorityTier();
                tierEligible.clear();
                tierEligible.add(c);
            } else if (c.priorityTier() == lowestTier) {
                tierEligible.add(c);
            }
        }
        if (tierEligible.isEmpty()) {
            return null;
        }

        List<GatewayChannelCandidate> bucket = tierEligible;
        if (inboundProtocol != null) {
            String inboundName = inboundProtocol.name();
            List<GatewayChannelCandidate> homomorphic = new ArrayList<>();
            for (GatewayChannelCandidate c : tierEligible) {
                if (c.isHomomorphicFor(inboundName)) {
                    homomorphic.add(c);
                }
            }
            if (!homomorphic.isEmpty()) {
                bucket = homomorphic;
            }
        }

        if (bucket.size() == 1) {
            return bucket.getFirst();
        }
        long totalWeight = 0L;
        for (GatewayChannelCandidate c : bucket) {
            totalWeight += Math.max(1, c.weight());
        }
        long pick = ThreadLocalRandom.current().nextLong(totalWeight);
        long cumulative = 0L;
        for (GatewayChannelCandidate c : bucket) {
            cumulative += Math.max(1, c.weight());
            if (pick < cumulative) {
                return c;
            }
        }
        return bucket.getLast();
    }
}
