package dev.youneskaouani.vestige.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for the failures Vestige reports to a client.
 *
 * <p>Carrying the status and the problem type on the exception keeps the mapping in one place and
 * stops the exception handler turning into a long chain of {@code instanceof}.
 */
public abstract class VestigeException extends RuntimeException {

    private final HttpStatus status;
    private final String problemType;

    protected VestigeException(HttpStatus status, String problemType, String message) {
        super(message);
        this.status = status;
        this.problemType = problemType;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * The path appended to the problem base URI to form the RFC 7807 {@code type}. Stable strings,
     * because clients are expected to branch on them.
     */
    public String problemType() {
        return problemType;
    }
}
