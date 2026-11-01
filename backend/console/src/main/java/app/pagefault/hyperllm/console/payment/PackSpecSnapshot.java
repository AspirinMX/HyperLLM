package app.pagefault.hyperllm.console.payment;

import java.util.List;
import java.util.UUID;

/** The SKU terms frozen onto an order; later catalog edits cannot move it. */
public record PackSpecSnapshot(
        UUID skuId,
        String code,
        String name,
        String unitType,
        List<String> applicableModels,
        long totalUnits,
        int validDays
) {
}
