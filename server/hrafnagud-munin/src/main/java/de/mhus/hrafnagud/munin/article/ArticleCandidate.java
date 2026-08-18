package de.mhus.hrafnagud.munin.article;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One feed entry after parsing and cleanup, before it meets the archive.
 *
 * <p>The boundary between "what a reader produced" and "what we store".
 * Every {@link de.mhus.hrafnagud.munin.ingest.SourceReader} produces these,
 * whatever format it read, so the ingest path knows nothing about RSS.
 */
@Value
@Builder
public class ArticleCandidate {

    /** Normalised article URL. Identity. */
    String url;

    /** URL exactly as the feed delivered it. */
    String originalUrl;

    /** Plain text, markup stripped. */
    String title;

    /** Plain text teaser, markup stripped and length-capped. */
    @Nullable String summary;

    @Nullable String author;

    @Nullable String imageUrl;

    /** Feed-level entry identifier. */
    @Nullable String guid;

    /** As claimed by the feed, already sanity-checked. */
    @Nullable Instant publishedAt;

    /** Language the feed or entry declared, if any. */
    @Nullable String declaredLanguage;

    /** Categories from the entry, verbatim. */
    @Singular
    List<String> categories;
}
