package ec.usfq.smartinventory.web;

import ec.usfq.smartinventory.application.ApiException;
import ec.usfq.smartinventory.domain.InsufficientStockException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Un unico lugar que traduce excepciones a problem+json. Nunca expone stack traces ni SQL. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemWriter problems;

    public GlobalExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    private ResponseEntity<Map<String, Object>> respond(int status, String code, String detail,
                                                        List<Map<String, String>> errors) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType(ProblemWriter.PROBLEM_JSON))
                .body(problems.body(status, code, detail, errors));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList();
        return respond(422, "VALIDATION_FAILED", "Request body failed validation", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> invalidParams(ConstraintViolationException ex) {
        var errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage())).toList();
        return respond(400, "INVALID_PARAMETER", "Invalid request parameter", errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> malformed(Exception ex) {
        return respond(400, "MALFORMED_REQUEST", "Malformed request (body, header or parameter type)", null);
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, Object>> insufficient(InsufficientStockException ex) {
        return respond(409, "INSUFFICIENT_STOCK", "Requested " + ex.requested() + ", assignable " + ex.available(), null);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return respond(ex.status(), ex.code(), ex.getMessage(), null);
    }

    /** Dependencia caida: 503 + Retry-After; el cliente puede reintentar con backoff. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<Map<String, Object>> dependencyDown(Exception ex) {
        log.error("database unavailable", ex);
        return ResponseEntity.status(503).header("Retry-After", "5")
                .contentType(MediaType.parseMediaType(ProblemWriter.PROBLEM_JSON))
                .body(problems.body(503, "DEPENDENCY_UNAVAILABLE", "Temporary failure; retry later", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("unhandled error", ex);
        return respond(500, "INTERNAL_ERROR", "Unexpected error; quote requestId when reporting", null);
    }
}
