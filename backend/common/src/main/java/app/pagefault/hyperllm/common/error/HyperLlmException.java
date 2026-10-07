package app.pagefault.hyperllm.common.error;

import lombok.Getter;

/**
 * Base runtime exception carrying a structured {@link ErrorCode}.
 */
@Getter
public class HyperLlmException extends RuntimeException {

    private final ErrorCode errorCode;

    public HyperLlmException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public HyperLlmException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public HyperLlmException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
