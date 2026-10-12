package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementAllocatorTest {

    private static final UUID PACK_A = UUID.randomUUID();
    private static final UUID PACK_B = UUID.randomUUID();
    private static final UUID GRANT_A = UUID.randomUUID();

    @Test
    void chargesNothingWhenTheAmountIsZero() {
        AllocationPlan plan = EntitlementAllocator.allocate(0L, 1_000L, snapshot(cash(10_000L)));

        assertThat(plan.legs()).isEmpty();
        assertThat(plan.shortfallMicros()).isZero();
        assertThat(plan.degraded()).isFalse();
    }

    @Test
    void reportsTheWholeAmountAsShortfallWhenNothingIsAvailable() {
        AllocationPlan plan = EntitlementAllocator.allocate(500L, 1_000L, EntitlementSnapshot.empty(0L));

        assertThat(plan.legs()).hasSize(1);
        assertThat(plan.legs().get(0).type()).isEqualTo(AllocationType.CASH);
        assertThat(plan.legs().get(0).micros()).isZero();
        assertThat(plan.shortfallMicros()).isEqualTo(500L);
        assertInvariants(plan, 500L);
    }

    @Test
    void aFullPackCoversTheWholeCharge() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                500L, 1_000L, new EntitlementSnapshot(List.of(tokenPack(PACK_A, 1_000L)), List.of(), 10_000L));

        assertThat(plan.legs()).hasSize(1);
        assertThat(plan.legs().get(0).type()).isEqualTo(AllocationType.PACK);
        assertThat(plan.legs().get(0).units()).isEqualTo(1_000L);
        assertThat(plan.legs().get(0).micros()).isEqualTo(500L);
        assertThat(plan.shortfallMicros()).isZero();
        assertInvariants(plan, 500L);
    }

    @Test
    void aPartialPackLeavesTheRemainderToCashAndShortfall() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                1_000L, 1_000L, new EntitlementSnapshot(List.of(tokenPack(PACK_A, 400L)), List.of(), 0L));

        assertThat(plan.legs()).extracting(AllocationLeg::type)
                .containsExactly(AllocationType.PACK, AllocationType.CASH);
        assertThat(plan.legs().get(0).units()).isEqualTo(400L);
        assertThat(plan.legs().get(0).micros()).isEqualTo(400L);
        assertThat(plan.legs().get(1).micros()).isZero();
        assertThat(plan.shortfallMicros()).isEqualTo(600L);
        assertInvariants(plan, 1_000L);
    }

    @Test
    void multiplePacksSplitProportionallyWithoutRoundingDrift() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                1_000L, 1_000L,
                new EntitlementSnapshot(List.of(tokenPack(PACK_A, 300L), tokenPack(PACK_B, 300L)), List.of(), 400L));

        assertThat(plan.legs()).extracting(AllocationLeg::type)
                .containsExactly(AllocationType.PACK, AllocationType.PACK, AllocationType.CASH);
        assertThat(plan.legs().get(0).micros()).isEqualTo(300L);
        assertThat(plan.legs().get(1).micros()).isEqualTo(300L);
        assertThat(plan.legs().get(2).micros()).isEqualTo(400L);
        assertThat(plan.shortfallMicros()).isZero();
        assertInvariants(plan, 1_000L);
    }

    @Test
    void tiersAreAppliedInOrderPackThenGrantThenCash() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                1_000L, 1_000L,
                new EntitlementSnapshot(
                        List.of(tokenPack(PACK_A, 400L)),
                        List.of(new EntitlementSnapshot.BonusBucket(GRANT_A, 300L)),
                        500L));

        assertThat(plan.legs()).extracting(AllocationLeg::type)
                .containsExactly(AllocationType.PACK, AllocationType.BONUS, AllocationType.CASH);
        assertThat(plan.legs().get(0).micros()).isEqualTo(400L);
        assertThat(plan.legs().get(1).micros()).isEqualTo(300L);
        assertThat(plan.legs().get(2).micros()).isEqualTo(300L);
        assertThat(plan.shortfallMicros()).isZero();
        assertInvariants(plan, 1_000L);
    }

    @Test
    void aCallPackCoversTheWholeRequestRegardlessOfTokenCount() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                777L, 5_000L, new EntitlementSnapshot(List.of(callPack(PACK_A, 1L)), List.of(), 0L));

        assertThat(plan.legs()).hasSize(1);
        assertThat(plan.legs().get(0).units()).isEqualTo(1L);
        assertThat(plan.legs().get(0).micros()).isEqualTo(777L);
        assertInvariants(plan, 777L);
    }

    @Test
    void grantsCoverWhatPacksLeaveAndCashCoversTheRest() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                1_000L, 1_000L,
                new EntitlementSnapshot(
                        List.of(tokenPack(PACK_A, 500L)),
                        List.of(new EntitlementSnapshot.BonusBucket(GRANT_A, 200L)),
                        400L));

        assertThat(plan.legs()).extracting(AllocationLeg::type)
                .containsExactly(AllocationType.PACK, AllocationType.BONUS, AllocationType.CASH);
        assertThat(plan.legs().get(1).micros()).isEqualTo(200L);
        assertThat(plan.legs().get(2).micros()).isEqualTo(300L);
        assertThat(plan.shortfallMicros()).isZero();
        assertInvariants(plan, 1_000L);
    }

    @Test
    void shortfallAppearsOnlyWhenEveryBucketIsExhausted() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                1_000L, 1_000L,
                new EntitlementSnapshot(
                        List.of(tokenPack(PACK_A, 100L)),
                        List.of(new EntitlementSnapshot.BonusBucket(GRANT_A, 100L)),
                        100L));

        assertThat(plan.shortfallMicros()).isEqualTo(700L);
        assertInvariants(plan, 1_000L);
        assertThat(plan.allocatedMicros()).isEqualTo(300L);
    }

    @Test
    void allocationIsDeterministicForTheSameSnapshot() {
        EntitlementSnapshot snapshot = new EntitlementSnapshot(
                List.of(tokenPack(PACK_A, 300L), tokenPack(PACK_B, 300L)),
                List.of(new EntitlementSnapshot.BonusBucket(GRANT_A, 100L)), 200L);

        AllocationPlan first = EntitlementAllocator.allocate(999L, 1_000L, snapshot);
        AllocationPlan second = EntitlementAllocator.allocate(999L, 1_000L, snapshot);

        assertThat(first.legs()).isEqualTo(second.legs());
        assertThat(first.shortfallMicros()).isEqualTo(second.shortfallMicros());
    }

    @Test
    void aPackLargerThanTheRequestStillOnlyConsumesWhatIsNeeded() {
        AllocationPlan plan = EntitlementAllocator.allocate(
                100L, 50L, new EntitlementSnapshot(List.of(tokenPack(PACK_A, 10_000L)), List.of(), 0L));

        assertThat(plan.legs()).hasSize(1);
        assertThat(plan.legs().get(0).units()).isEqualTo(50L);
        assertThat(plan.legs().get(0).micros()).isEqualTo(100L);
        assertInvariants(plan, 100L);
    }

    private static void assertInvariants(AllocationPlan plan, long charged) {
        assertThat(plan.chargedMicros()).isEqualTo(charged);
        assertThat(plan.allocatedMicros() + plan.shortfallMicros())
                .as("A1: allocated plus shortfall equals the charge")
                .isEqualTo(charged);
        long cashLegs = plan.legs().stream().filter(leg -> leg.type() == AllocationType.CASH).count();
        assertThat(cashLegs).as("A4: at most one cash leg").isLessThanOrEqualTo(1L);
        if (cashLegs == 1) {
            assertThat(plan.legs().get(plan.legs().size() - 1).type())
                    .as("A4: cash is always last")
                    .isEqualTo(AllocationType.CASH);
        }
    }

    private static EntitlementSnapshot.PackBucket tokenPack(UUID id, long units) {
        return new EntitlementSnapshot.PackBucket(id, PackUnitType.TOKENS, units);
    }

    private static EntitlementSnapshot.PackBucket callPack(UUID id, long units) {
        return new EntitlementSnapshot.PackBucket(id, PackUnitType.CALLS, units);
    }

    private static EntitlementSnapshot snapshot(long cash) {
        return EntitlementSnapshot.empty(cash);
    }

    private static long cash(long micros) {
        return micros;
    }
}
