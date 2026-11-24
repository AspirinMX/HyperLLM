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
    CHANNEL_CAPACITY_EXCEEDED(429, "No upstream channel has capacity for this attempt"),
    PRICING_WINDOW_OVERLAP(409, "Pricing effective window overlaps an existing rule"),

    BILLING_INSUFFICIENT_BALANCE(402, "Insufficient wallet balance"),
    BILLING_INVALID_AMOUNT(400, "Invalid billing amount"),
    RATE_LIMITED(429, "Rate limit or concurrency limit exceeded"),
    PROJECT_BUDGET_EXCEEDED(429, "Project monthly budget exhausted"),
    PROJECT_BUDGET_UNAVAILABLE(503, "Project budget state cannot be verified; retry shortly"),
    ANALYTICS_UNAVAILABLE(503, "The usage warehouse is unavailable; retry shortly"),
    PROTOCOL_INVALID_REQUEST(400, "Invalid request payload"),
    PROTOCOL_UNSUPPORTED_FEATURE(400, "Requested feature is not supported across protocol translation"),
    UPSTREAM_ERROR(502, "Upstream provider error"),
    CONFLICT_OPTIMISTIC_LOCK(409, "Resource was modified concurrently"),
    LEDGER_BACKLOG(503, "The billing ledger is behind; retry shortly"),
    OUTBOX_UNAVAILABLE(503, "The billing outbox is unavailable; retry shortly"),
    IDEMPOTENT_IN_FLIGHT(409, "An identical request is still in flight"),
    IDEMPOTENT_REPLAYED(409, "Duplicate request rejected by idempotency key"),

    PAYMENT_ORDER_NOT_FOUND(404, "Payment order not found"),
    PAYMENT_ORDER_CONFLICT(409, "The idempotency key was used with different terms"),
    PAYMENT_CHANNEL_DISABLED(400, "The payment channel is not enabled"),
    PAYMENT_CHANNEL_UNAVAILABLE(502, "The payment channel did not answer"),
    PAYMENT_VERIFICATION_FAILED(400, "The payment notification could not be verified"),
    PAYMENT_AMOUNT_MISMATCH(409, "The paid amount or currency does not match the order"),
    PAYMENT_STATE_CONFLICT(409, "The payment is in a conflicting state"),
    PAYMENT_ANOMALY_NOT_FOUND(404, "Payment anomaly not found"),
    PACK_SKU_NOT_FOUND(404, "Resource pack SKU not found"),
    PACK_SKU_CODE_EXISTS(409, "Resource pack SKU code already exists"),
    PACK_SKU_NOT_PURCHASABLE(409, "Resource pack SKU is not on sale"),
    VOUCHER_CODE_INVALID(400, "The redemption code is not valid"),
    VOUCHER_LIMIT_REACHED(409, "The voucher redemption limit has been reached"),
    VOUCHER_NOT_FOUND(404, "Voucher not found"),
    ALERT_RULE_NOT_FOUND(404, "Alert rule not found"),
    ALERT_RULE_EXISTS(409, "An identical alert rule already exists"),
    NOTIFICATION_ENDPOINT_NOT_FOUND(404, "Notification endpoint not found"),
    NOTIFICATION_ENDPOINT_IN_USE(409, "The notification endpoint is referenced by an active rule"),
    ALERT_DELIVERY_NOT_FOUND(404, "Notification delivery not found"),
    RECONCILIATION_FINDING_NOT_FOUND(404, "Reconciliation finding not found"),
    BUDGET_MONTH_NOT_FOUND(404, "Project budget month not found");

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