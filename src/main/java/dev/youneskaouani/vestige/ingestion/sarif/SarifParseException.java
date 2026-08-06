package dev.youneskaouani.vestige.ingestion.sarif;

/** Thrown when an uploaded report is not a SARIF document Vestige can read. */
public class SarifParseException extends RuntimeException {

    public SarifParseException(String message) {
        super(message);
    }

    public SarifParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
