package app.pagefault.hyperllm.console.web;

import app.pagefault.hyperllm.common.error.HyperLlmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ConsoleProblemAdvice {

    private final ConsoleProblemSupport problemSupport;

    @ExceptionHandler(HyperLlmException.class)
    public ResponseEntity<ProblemDetail> handleHyperLlm(HyperLlmException ex) {
        ProblemDetail body = problemSupport.build(ex.getErrorCode(), ex.getMessage(), List.of());
        return ResponseEntity.status(ex.getErrorCode().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<ConsoleProblemSupport.FieldErrorItem> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> new ConsoleProblemSupport.FieldErrorItem(err.getField(), err.getDefaultMessage()))
                .toList();
        ProblemDetail body = problemSupport.build(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Validation Failed",
                "One or more request fields failed validation.",
                fieldErrors
        );
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        ProblemDetail body = problemSupport.build(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Malformed Request Body",
                "Request JSON could not be parsed.",
                List.of()
        );
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException ex) {
        ProblemDetail body = problemSupport.build(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_CONFLICT",
                "Resource Conflict",
                "The requested change conflicts with an existing record.",
                List.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled console exception", ex);
        ProblemDetail body = problemSupport.build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal Server Error",
                "An unexpected error occurred.",
                List.of()
        );
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
