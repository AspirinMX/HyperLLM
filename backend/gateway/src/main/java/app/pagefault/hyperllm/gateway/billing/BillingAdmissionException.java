package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import lombok.Getter;

/** The request can be retried because billing refused admission before reserving funds. */
@Getter
public class BillingAdmissionException extends HyperLlmException {

    private final long retryAfterSeconds;

    public BillingAdmissionException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
