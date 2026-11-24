package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
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

    @Test
    void prefersHomomorphicChannelWithinSameTierBeforeFallingBackToHeterogeneous() {
        UUID tier1Homo = UuidV7.next();
        UUID tier1Hetero = UuidV7.next();
        UUID tier2Homo = UuidV7.next();

        GatewayChannelCandidate homo1 = new GatewayChannelCandidate(
                UuidV7.next(), tier1Homo, "ant-t1", "us-east", "ANTHROPIC", ProtocolFamily.ANTHROPIC,
                List.of("ANTHROPIC_MESSAGES"), "ANTHROPIC_MESSAGES", null,
                AuthScheme.X_API_KEY, "https://api.anthropic.com", "claude-sonnet-4-5",
                (short) 1, 10, 10_000, new byte[0], (short) 1, "{}", null, null, null
        );
        GatewayChannelCandidate hetero1 = new GatewayChannelCandidate(
                UuidV7.next(), tier1Hetero, "oai-t1", "us-east", "OPENAI", ProtocolFamily.OPENAI,
                List.of("OPENAI_CHAT", "OPENAI_RESPONSES"), "OPENAI_CHAT", null,
                AuthScheme.BEARER, "https://api.openai.com/v1", "gpt-5",
                (short) 1, 900, 10_000, new byte[0], (short) 1, "{}", null, null, null
        );
        GatewayChannelCandidate homo2 = new GatewayChannelCandidate(
                UuidV7.next(), tier2Homo, "ant-t2", "eu-west", "ANTHROPIC", ProtocolFamily.ANTHROPIC,
                List.of("ANTHROPIC_MESSAGES"), "ANTHROPIC_MESSAGES", null,
                AuthScheme.X_API_KEY, "https://api.anthropic.com", "claude-sonnet-4-5",
                (short) 2, 100, 10_000, new byte[0], (short) 1, "{}", null, null, null
        );

        List<GatewayChannelCandidate> pool = List.of(hetero1, homo2, homo1);
        for (int i = 0; i < 20; i++) {
            assertThat(selector.selectNext(pool, Set.of(), ProtocolKind.ANTHROPIC_MESSAGES).channelId())
                    .isEqualTo(tier1Homo);
        }
        // Once Tier 1 homomorphic is excluded, fall back to Tier 1 heterogeneous before dropping to Tier 2
        assertThat(selector.selectNext(pool, Set.of(tier1Homo), ProtocolKind.ANTHROPIC_MESSAGES).channelId())
                .isEqualTo(tier1Hetero);
        // Once Tier 1 is completely exhausted, pick Tier 2 homomorphic
        assertThat(selector.selectNext(pool, Set.of(tier1Homo, tier1Hetero), ProtocolKind.ANTHROPIC_MESSAGES).channelId())
                .isEqualTo(tier2Homo);
    }
}
