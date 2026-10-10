package app.pagefault.hyperllm.domain.pricing;

/**
 * Where the unit prices of a request came from. Kept in the allocation detail so a bill can be
 * explained without joining any table.
 */
public enum PricingSource {
    /** No override: the organisation's own discount ratio applied to the model's list prices. */
    ORG,
    /** No override and no organisation ratio: the customer tier's default discount. */
    TIER,
    /** Model-level negotiated discount replaced the organisation and tier ratios. */
    OVERRIDE_DISCOUNT,
    /** Model-level negotiated fixed unit prices; discounts do not participate. */
    OVERRIDE_FIXED
}
