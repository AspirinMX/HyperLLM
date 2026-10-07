package app.pagefault.hyperllm.console.web;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.id.UuidV7;
import io.micrometer.tracing.Tracer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Builds RFC 9457 ProblemDetail payloads enriched with HyperLLM error code, traceId, and field errors.
 */
@Component
public class ConsoleProblemSupport {

    public record FieldErrorItem(String field, String message) {
    }

    private final Tracer tracer;

    public ConsoleProblemSupport(org.springframework.beans.factory.ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    public ProblemDetail build(ErrorCode errorCode, String detail, List<FieldErrorItem> fieldErrors) {
        return build(HttpStatus.valueOf(errorCode.httpStatus()), errorCode.name(), errorCode.defaultMessage(), detail, fieldErrors);
    }

    public ProblemDetail build(
            HttpStatus status,
            String code,
            String title,
            String detail,
            List<FieldErrorItem> fieldErrors
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://hyperllm.dev/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("traceId", resolveTraceId());
        problem.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        problem.setProperty("fieldErrors", fieldErrors == null ? List.of() : fieldErrors);
        return problem;
    }

    private String resolveTraceId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().traceId();
        }
        return UuidV7.next().toString().replace("-", "");
    }
}
