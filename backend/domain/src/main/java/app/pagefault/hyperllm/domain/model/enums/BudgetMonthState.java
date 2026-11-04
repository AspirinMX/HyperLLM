package app.pagefault.hyperllm.domain.model.enums;

/**
 * Whether a project month can be trusted as a complete statement of what the project spent.
 *
 * <p>{@code UNVERIFIED} is written when a settlement had to be closed without its exact value. The
 * amount that is known is still folded, but the month stays marked until an operator resolves it,
 * and a project with a hard cap refuses admission while its month is in this state.
 */
public enum BudgetMonthState {
    CONFIRMED,
    UNVERIFIED
}
