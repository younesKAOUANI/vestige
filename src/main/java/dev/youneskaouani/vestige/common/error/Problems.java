package dev.youneskaouani.vestige.common.error;

import org.springframework.http.HttpStatus;

/** The concrete failures the API reports, and the RFC 7807 problem types that name them. */
public final class Problems {

    /** Base URI the {@code type} of every problem is resolved against. */
    public static final String BASE_URI = "https://vestige.youneskaouani.dev/problems/";

    private Problems() {
    }

    /** The caller asked for something that does not exist, or that its tenant cannot see. */
    public static final class NotFound extends VestigeException {
        public NotFound(String what, Object id) {
            super(HttpStatus.NOT_FOUND, "not-found", what + " " + id + " was not found");
        }
    }

    /** The request was well-formed but asks for something the current state does not allow. */
    public static final class Conflict extends VestigeException {
        public Conflict(String message) {
            super(HttpStatus.CONFLICT, "conflict", message);
        }
    }

    /** The request itself is wrong: a bad SARIF document, an impossible transition. */
    public static final class BadRequest extends VestigeException {
        public BadRequest(String message) {
            super(HttpStatus.BAD_REQUEST, "bad-request", message);
        }
    }

    /** No usable API key was presented. */
    public static final class Unauthorized extends VestigeException {
        public Unauthorized(String message) {
            super(HttpStatus.UNAUTHORIZED, "unauthorized", message);
        }
    }

    /** A key was presented but does not permit this. */
    public static final class Forbidden extends VestigeException {
        public Forbidden(String message) {
            super(HttpStatus.FORBIDDEN, "forbidden", message);
        }
    }
}
