package app.pagefault.hyperllm.common.error;

/**
 * Machine-readable platform error codes mapped to HTTP statuses across modules.
 */
public enum ErrorCode {

    AUTH_INVALID_CREDENTIALS(401, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED(401, "Token expired"),
    AUTH_TOKEN_REUSE_DETECTED(401, "Refresh token reuse detected"),
    AUTH_MFA_REQUIRED(403, "MFA verification required"),
    AUTH_FORBIDDEN(403, "Access denied"),

    TENANT_ORG_NOT_FOUND(404, "Organization not found"),
    TENANT_SLUG_EXISTS(409, "Organization slug already exists"),
    TENANT_PROJECT_NOT_FOUND(404, "Project not found"),
    TENANT_PROJECT_DUPLICATE(409, "Project name already exists in organization"),
    TENANT_MEMBER_EXISTS(409, "Member already exists in organization"),

    KEY_NOT_FOUND(404, "API key not found"),
    KEY_ALREADY_REVOKED(403, "API key already revoked"),

    MODEL_NOT_FOUND(404, "Virtual model not found"),
    MODEL_CODE_EXISTS(409, "Virtual model code already exists"),
    CHANNEL_NOT_FOUND(404, "Channel not found"),
    CHANNEL_PROBE_FAILED(502, "Upstream channel probe failed"),
    PRICING_WINDOW_OVERLAP(409, "Pricing effective window overlaps an existing rule"),

    BILLING_INSUFFICIENT_BALANCE(402, "Insufficient wallet balance"),
    BILLING_INVALID_AMOUNT(400, "Invalid billing amount"),
    CONFLICT_OPTIMISTIC_LOCK(409, "Resource was modified concurrently");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
