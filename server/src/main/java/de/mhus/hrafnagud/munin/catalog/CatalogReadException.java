package de.mhus.hrafnagud.munin.catalog;

/**
 * A catalogue could not be resolved.
 *
 * <p>The distinction that matters: this means "we do not know what the
 * directory offers", never "the directory offers nothing". The caller keeps
 * the lists it already had and retries later.
 */
public class CatalogReadException extends RuntimeException {

    public CatalogReadException(String message) {
        super(message);
    }

    public CatalogReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
