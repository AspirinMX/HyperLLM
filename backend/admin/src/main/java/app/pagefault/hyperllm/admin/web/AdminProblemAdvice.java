package app.pagefault.hyperllm.admin.web;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Slf4j
@RestControllerAdvice
public class AdminProblemAdvice {

    public record FieldErrorItem(String field, String message) {
    }

    private final Tracer tracer;

    public AdminProblemAdvice(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    public ProblemDetail buildProblem(ErrorCode errorCode, String detail, List<FieldErrorItem> fieldErrors) {
        return buildProblem(HttpStatus.valueOf(errorCode.httpStatus()), errorCode.name(), errorCode.defaultMessage(), detail, fieldErrors);
    }

    public ProblemDetail buildProblem(
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

    @ExceptionHandler(HyperLlmException.class)
    public ResponseEntity<ProblemDetail> handleHyperLlm(HyperLlmException ex) {
        return ResponseEntity.status(ex.getErrorCode().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(ex.getErrorCode(), ex.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> new FieldErrorItem(err.getField(), err.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation Failed",
                        "One or more request fields failed validation.", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMalformed(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed Request Body",
                        "Request JSON could not be parsed.", List.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", "Resource Conflict",
                        "The requested change conflicts with an existing record.", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled admin exception", ex);
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal Server Error",
                        "An unexpected error occurred.", List.of()));
    }

    private String resolveTraceId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().traceId();
        }
        return UuidV7.next().toString().replace("-", "");
    }
}
