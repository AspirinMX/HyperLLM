package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.domain.billing.EntitlementSnapshot;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementMirrorDecodeTest {

    @Test
    void subtractsTheHeldAmountFromEveryBatchAndFromCash() {
        UUID packId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        List<String> reply = List.of(
                "OK", "1000", "300", "ACTIVE",
                "1", packId.toString(), "50", "10", "TOKENS", "4102444800000",
                "1", grantId.toString(), "7000", "500", "4102444800000",
                "1");

        EntitlementMirrorService.Mirror mirror = EntitlementMirrorService.decode(reply);

        assertThat(mirror.orgStatus()).isEqualTo("ACTIVE");
        assertThat(mirror.snapshot().cashMicros()).isEqualTo(700L);
        assertThat(mirror.snapshot().packs())
                .containsExactly(new EntitlementSnapshot.PackBucket(packId, PackUnitType.TOKENS, 40L));
        assertThat(mirror.snapshot().grants())
                .containsExactly(new EntitlementSnapshot.BonusBucket(grantId, 6500L));
    }

    @Test
    void reportsAMissingMirrorAsSuch() {
        EntitlementMirrorService.Mirror mirror =
                EntitlementMirrorService.decode(List.of("MISS_WALLET", "0", "0", ""));

        assertThat(mirror.orgStatus()).isEqualTo("MISS_WALLET");
        assertThat(mirror.snapshot().cashMicros()).isZero();
        assertThat(mirror.snapshot().packs()).isEmpty();
    }

    @Test
    void neverReportsANegativeAvailableAmount() {
        UUID packId = UUID.randomUUID();
        List<String> reply = List.of(
                "OK", "100", "500", "ACTIVE",
                "1", packId.toString(), "10", "40", "TOKENS", "4102444800000",
                "0",
                "1");

        EntitlementMirrorService.Mirror mirror = EntitlementMirrorService.decode(reply);

        assertThat(mirror.snapshot().cashMicros()).isZero();
        assertThat(mirror.snapshot().packs().get(0).remainingUnits()).isZero();
    }

    @Test
    void parsesTheFrozenLegsOfAHold() {
        List<String[]> legs = EntitlementMirrorService.decodeLegs(
                "PACK|0193a000-0000-7000-8000-000000000001|10|500;"
                        + "BONUS|0193a000-0000-7000-8000-000000000002|0|200;"
                        + "CASH||0|300");

        assertThat(legs).hasSize(3);
        assertThat(legs.get(0)).containsExactly("PACK", "0193a000-0000-7000-8000-000000000001", "10", "500");
        assertThat(legs.get(2)).containsExactly("CASH", "", "0", "300");
    }
}
