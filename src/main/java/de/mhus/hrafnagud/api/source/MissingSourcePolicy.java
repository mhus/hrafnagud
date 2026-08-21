package de.mhus.hrafnagud.api.source;

/**
 * What a source-list refresh does with sources it previously imported that
 * are no longer in the list.
 *
 * <p>Default is {@link #DISABLE}: effective (we stop polling a feed the
 * directory dropped) without being destructive (the articles we already
 * collected keep their source row, and a human can re-enable it). A list
 * that briefly serves a truncated response would otherwise delete half the
 * registry.
 */
public enum MissingSourcePolicy {

    /** Set {@code enabled=false}, keep the source row. The default. */
    DISABLE,

    /** Leave the source untouched and keep polling it. */
    KEEP,

    /** Delete the source row. Articles already collected are not deleted. */
    DELETE
}
