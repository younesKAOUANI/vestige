package dev.youneskaouani.vestige.common.error;

import dev.youneskaouani.vestige.ingestion.sarif.SarifParseException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 7807 {@code application/problem+json} response.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring's own failures - unreadable
 * bodies, missing parameters, failed validation - come out in the same shape as Vestige's, so a
 * client only has to understand one error format.
 *
 * <p>Unexpected exceptions are logged with their stack trace and answered with a deliberately
 * uninformative body. An internal error message is exactly the kind of thing that leaks a schema.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VestigeException.class)
    public ProblemDetail handleVestigeException(
            VestigeException exception, HttpServletRequest request) {
        return problem(
                exception.status(), exception.problemType(), exception.getMessage(), request);
    }

    @ExceptionHandler(SarifParseException.class)
    public ProblemDetail handleSarifParseException(
            SarifParseException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-sarif", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", exception.getMessage(), request);
    }

    /**
     * A unique-constraint violation that reaches this point is a race the service did not expect to
     * lose. The idempotent submission path handles its own collisions; anything else is a genuine
     * conflict and the client may retry.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Constraint violation on {}", request.getRequestURI(), exception);
        return problem(
                HttpStatus.CONFLICT,
                "conflict",
                "The request conflicts with the current state of the resource",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "Unhandled exception on {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "The request could not be completed",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status, String type, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(Problems.BASE_URI + type));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
