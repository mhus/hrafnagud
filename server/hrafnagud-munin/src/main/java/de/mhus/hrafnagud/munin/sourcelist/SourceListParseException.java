package de.mhus.hrafnagud.munin.sourcelist;

/**
 * The document is not the declared format at all — an HTML error page
 * served instead of OPML, a truncated download, a login wall.
 *
 * <p>Distinct from an entry that could not be used, which is a warning.
 * This one means the refresh produced nothing and the list's configuration
 * is probably wrong.
 */
public class SourceListParseException extends RuntimeException {

    public SourceListParseException(String message) {
        super(message);
    }

    public SourceListParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
