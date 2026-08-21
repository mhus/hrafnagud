package de.mhus.hrafnagud.api.catalog;

/**
 * What happens to a source list that a catalogue no longer offers.
 *
 * <p>Deliberately its own enum rather than a reuse of
 * {@code MissingSourcePolicy}, although the three values read the same: the
 * two layers answer the question about different things, and a catalogue may
 * one day want an option that makes no sense for a single feed. Sharing the
 * type would have made that a change to both.
 */
public enum MissingListPolicy {

    /**
     * Stop refreshing it, keep everything. The default, because a directory
     * dropping an entry is usually an editorial decision about the directory
     * and not a statement that the feed is dead — and the archive it filled
     * stays worth having.
     */
    DISABLE,

    /** Leave it running. For a catalogue used as a suggestion, not an authority. */
    KEEP,

    /**
     * Delete the list. Its sources survive as unmanaged, exactly as they do
     * when a list is deleted by hand — deleting collected articles as a side
     * effect of a directory edit is never what was meant.
     */
    DELETE
}
