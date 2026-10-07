package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSelectorTest {

    private final ChannelSelector selector = new ChannelSelector();

    private static GatewayChannelCandidate candidate(UUID channelId, String name, short tier, int weight) {
        return new GatewayChannelCandidate(
                UuidV7.next(), channelId, name, "us-east", "OPENAI", ProtocolFamily.OPENAI,
                AuthScheme.BEARER, "https://api.openai.com/v1", "gpt-4o",
                tier, weight, 10_000, new byte[0], (short) 1, "{}", null, null, null
        );
    }

    @Test
    void prefersLowestPriorityTierAndFallsBackWhenExcluded() {
        UUID tier1A = UuidV7.next();
        UUID tier1B = UuidV7.next();
        UUID tier2 = UuidV7.next();
        List<GatewayChannelCandidate> list = List.of(
                candidate(tier2, "backup", (short) 2, 100),
                candidate(tier1A, "primary-a", (short) 1, 80),
                candidate(tier1B, "primary-b", (short) 1, 20)
        );

        for (int i = 0; i < 20; i++) {
            GatewayChannelCandidate picked = selector.selectNext(list, Set.of());
            assertThat(picked.priorityTier()).isEqualTo((short) 1);
        }

        GatewayChannelCandidate fallback = selector.selectNext(list, Set.of(tier1A, tier1B));
        assertThat(fallback).isNotNull();
        assertThat(fallback.channelId()).isEqualTo(tier2);

        assertThat(selector.selectNext(list, Set.of(tier1A, tier1B, tier2))).isNull();
    }
}
