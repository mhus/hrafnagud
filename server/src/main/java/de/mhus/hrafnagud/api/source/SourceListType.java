package de.mhus.hrafnagud.api.source;

/**
 * Format of a source list — a document that enumerates feeds so the
 * registry can be maintained by pointing at a directory instead of by
 * hand.
 *
 * <ul>
 *   <li>{@link #OPML} — the interchange format every feed reader exports.
 *       Nested {@code <outline>} elements; the enclosing outline's text
 *       becomes the imported source's category.</li>
 *   <li>{@link #TEXT} — one feed URL per line, {@code #} starts a comment.
 *       The lowest-effort format for a hand-kept list in a git repo.</li>
 * </ul>
 */
public enum SourceListType {

    /** OPML 1.0/2.0 outline document. */
    OPML,

    /** Plain text, one URL per line, {@code #} comments. */
    TEXT
}
