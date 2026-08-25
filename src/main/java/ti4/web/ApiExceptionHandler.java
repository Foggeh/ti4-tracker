package ti4.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Puts a human-readable {@code message} in every API error body.
 *
 * <p>Spring Boot's default {@code /error} dispatch drops the reason from a
 * {@link ResponseStatusException} even with {@code server.error.include-message=always},
 * which would leave the UI showing a bare "Bad Request" mid-game. Handling it
 * here is explicit and does not depend on that behaviour.
 *
 * <p>Scoped to {@link ApiController}'s package on purpose. Left global, the
 * catch-all below also swallowed Spring's static-resource handling and turned
 * every missing card image into a 500 instead of a 404.
 */
@RestControllerAdvice(basePackageClasses = ApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();
        return ResponseEntity.status(e.getStatusCode()).body(body(e.getStatusCode().value(), message));
    }

    /**
     * Framework exceptions that already carry their own status -- most usefully
     * NoResourceFoundException, a 404 for a missing card image.
     *
     * <p>Without this they fall through to the catch-all below and every 404
     * becomes a 500, which is both wrong and actively misleading when debugging
     * a mistyped image filename.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> handleErrorResponse(ErrorResponseException e) {
        int status = e.getStatusCode().value();
        String detail = e.getBody() != null ? e.getBody().getDetail() : null;
        return ResponseEntity.status(status)
                .body(body(status, detail != null ? detail : e.getStatusCode().toString()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(body(400, "Missing required parameter: " + e.getParameterName()));
    }

    /** A malformed parameter is the caller's fault, so 400 rather than 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        String value = String.valueOf(e.getValue());
        return ResponseEntity.badRequest().body(body(400,
                "Parameter '" + e.getName() + "' could not be read from "
                        + (value.isEmpty() ? "an empty value" : "'" + value + "'")));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(DataIntegrityViolationException e) {
        log.warn("Constraint violation", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(409, "That would break a database constraint -- "
                        + "usually a duplicate name, or a player/objective that no longer exists."));
    }

    /**
     * Last resort. This is a LAN tool for our own table, so showing the real
     * message beats hiding it -- but it is still logged with a stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAnything(Exception e) {
        log.error("Unhandled error", e);
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.internalServerError().body(body(500, message));
    }

    private static Map<String, Object> body(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        return body;
    }
}
