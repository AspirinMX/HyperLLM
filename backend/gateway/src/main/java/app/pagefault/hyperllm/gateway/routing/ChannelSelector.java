package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Selects an upstream channel candidate using ascending {@code priorityTier} and weighted random selection.
 */
@Component
public class ChannelSelector {

    public GatewayChannelCandidate selectNext(
            List<GatewayChannelCandidate> candidates,
            Set<UUID> excludedChannelIds
    ) {
        List<GatewayChannelCandidate> eligible = new ArrayList<>();
        short lowestTier = Short.MAX_VALUE;
        for (GatewayChannelCandidate c : candidates) {
            if (excludedChannelIds != null && excludedChannelIds.contains(c.channelId())) {
                continue;
            }
            if (c.priorityTier() < lowestTier) {
                lowestTier = c.priorityTier();
                eligible.clear();
                eligible.add(c);
            } else if (c.priorityTier() == lowestTier) {
                eligible.add(c);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        if (eligible.size() == 1) {
            return eligible.getFirst();
        }
        long totalWeight = 0L;
        for (GatewayChannelCandidate c : eligible) {
            totalWeight += Math.max(1, c.weight());
        }
        long pick = ThreadLocalRandom.current().nextLong(totalWeight);
        long cumulative = 0L;
        for (GatewayChannelCandidate c : eligible) {
            cumulative += Math.max(1, c.weight());
            if (pick < cumulative) {
                return c;
            }
        }
        return eligible.getLast();
    }
}
