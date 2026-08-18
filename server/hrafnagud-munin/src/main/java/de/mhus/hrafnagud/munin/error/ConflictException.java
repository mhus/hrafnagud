package de.mhus.hrafnagud.munin.error;

/**
 * The request collides with existing state — most often a feed URL that is
 * already registered under a different name. Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
