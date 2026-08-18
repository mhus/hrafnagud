package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.api.source.SourceListType;

/**
 * Turns a source-list document into feed candidates.
 *
 * <p>One bean per {@link SourceListType}; the registry keys on
 * {@link #type()}, so a new list format is a new bean and nothing else.
 */
public interface SourceListParser {

    SourceListType type();

    /**
     * Parses {@code body}, collecting rejects as warnings rather than
     * throwing.
     *
     * @param maxEntries stop after this many usable entries
     * @throws SourceListParseException when the document as a whole is not
     *         of this format — as opposed to individual bad entries
     */
    ParsedSourceList parse(String body, int maxEntries);
}
