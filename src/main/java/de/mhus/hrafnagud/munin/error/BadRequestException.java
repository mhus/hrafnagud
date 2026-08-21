package de.mhus.hrafnagud.munin.error;

/**
 * The request is malformed in a way bean validation cannot express — an
 * unusable URL, a nonsensical time window. Mapped to HTTP 400.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
