package de.mhus.hrafnagud.munin.ingest;

import de.mhus.hrafnagud.api.source.SourceType;
import de.mhus.hrafnagud.munin.source.SourceDocument;

/**
 * Reads one source and returns article candidates.
 *
 * <p>One bean per {@link SourceType}. Implementations own the transport and
 * the format; they do not touch the archive, do not deduplicate and do not
 * resolve languages — those are the same regardless of where the entries
 * came from, and belong to {@link FeedIngestService}.
 */
public interface SourceReader {

    SourceType type();

    /**
     * Fetches and parses. Never throws for ordinary failure — an
     * unreachable host, a 404, an HTML page where a feed was expected are
     * all everyday events and come back as an outcome.
     */
    SourceReadResult read(SourceDocument source);
}
