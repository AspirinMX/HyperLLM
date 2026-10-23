package app.pagefault.hyperllm.common.billing;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyBonusBatchTest {

    @Test
    void derivesAStableIdPerOrganisation() {
        UUID orgId = UUID.randomUUID();

        assertThat(LegacyBonusBatch.idFor(orgId))
                .isEqualTo(LegacyBonusBatch.idFor(orgId))
                .isNotEqualTo(LegacyBonusBatch.idFor(UUID.randomUUID()));
    }

    @Test
    void recognisesItsOwnBatchAndNothingElse() {
        UUID orgId = UUID.randomUUID();

        assertThat(LegacyBonusBatch.isLegacy(orgId, LegacyBonusBatch.idFor(orgId))).isTrue();
        assertThat(LegacyBonusBatch.isLegacy(orgId, UUID.randomUUID())).isFalse();
        assertThat(LegacyBonusBatch.isLegacy(orgId, null)).isFalse();
    }
}
