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
    AUTH_INVALID_OTP(400, "Invalid or expired verification code"),
    AUTH_OTP_RATE_LIMITED(429, "Verification code requested too frequently"),
    AUTH_EMAIL_EXISTS(409, "Email is already registered"),
    AUTH_IP_BLOCKED(403, "Source IP address is not allowed"),
    AUTH_INVALID_KEY(401, "Invalid API key"),

    TENANT_ORG_NOT_FOUND(404, "Organization not found"),
    TENANT_ORG_SUSPENDED(403, "Organization is not active"),
    TENANT_SLUG_EXISTS(409, "Organization slug already exists"),
    TENANT_PROJECT_NOT_FOUND(404, "Project not found"),
    TENANT_PROJECT_DUPLICATE(409, "Project name already exists in organization"),
    TENANT_MEMBER_EXISTS(409, "Member already exists in organization"),
    TENANT_INVITATION_INVALID(400, "Invitation is invalid or expired"),

    KEY_NOT_FOUND(404, "API key not found"),
    KEY_ALREADY_REVOKED(403, "API key already revoked"),
    KEY_EXPIRED(401, "API key expired"),
    KEY_QUOTA_EXCEEDED(403, "API key quota limit exceeded"),

    MODEL_NOT_FOUND(404, "Virtual model not found"),
    MODEL_PRICING_MISSING(503, "No active pricing rule for this model"),
    MODEL_NOT_ALLOWED(403, "Model is not permitted for this API key or project"),
    MODEL_CODE_EXISTS(409, "Virtual model code already exists"),
    CHANNEL_NOT_FOUND(404, "Channel not found"),
    CHANNEL_NAME_EXISTS(409, "Channel name already exists"),
    CHANNEL_PROBE_FAILED(502, "Upstream channel probe failed"),
    CHANNEL_UNAVAILABLE(503, "No healthy upstream channel available"),
    PRICING_WINDOW_OVERLAP(409, "Pricing effective window overlaps an existing rule"),

    BILLING_INSUFFICIENT_BALANCE(402, "Insufficient wallet balance"),
    BILLING_INVALID_AMOUNT(400, "Invalid billing amount"),
    RATE_LIMITED(429, "Rate limit or concurrency limit exceeded"),
    PROTOCOL_INVALID_REQUEST(400, "Invalid request payload"),
    UPSTREAM_ERROR(502, "Upstream provider error"),
    CONFLICT_OPTIMISTIC_LOCK(409, "Resource was modified concurrently"),
    LEDGER_BACKLOG(503, "The billing ledger is behind; retry shortly"),
    OUTBOX_UNAVAILABLE(503, "The billing outbox is unavailable; retry shortly"),
    IDEMPOTENT_IN_FLIGHT(409, "An identical request is still in flight"),
    IDEMPOTENT_REPLAYED(409, "Duplicate request rejected by idempotency key");

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
