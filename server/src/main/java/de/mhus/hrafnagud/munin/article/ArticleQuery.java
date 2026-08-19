package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.api.article.ContentStatus;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Filter for an article listing. Every field is optional; unset means
 * unfiltered.
 *
 * <p>A value object rather than a long parameter list so that adding a
 * filter does not ripple through the controller, the service and every
 * test.
 */
@Value
@Builder
public class ArticleQuery {

    /** Articles delivered by this source, at any point. */
    @Nullable String sourceName;

    /** BCP-47 primary subtag. */
    @Nullable String language;

    /** Exact match against one of the article's verbatim categories. */
    @Nullable String category;

    /**
     * Place the publisher sits in, at any level — {@code m49:142} finds every
     * article from an Asian publisher, {@code iso:SG} only Singaporean ones.
     *
     * <p>One field for every level because the article stores the whole
     * containment path, so the query does not have to know which rung it was
     * handed. <b>Origin, not subject:</b> this does not find articles
     * <em>about</em> Asia.
     */
    @Nullable String originPlace;

    /** Full-text search over title and teaser. */
    @Nullable String text;

    @Nullable ContentStatus contentStatus;

    /** Lower bound on {@code firstSeenAt}, inclusive. */
    @Nullable Instant since;

    /**
     * Lower bound on {@code publishedAt}, inclusive.
     *
     * <p>Deliberately separate from {@link #since}: "collected since" and
     * "published since" are different questions and routinely give
     * different answers — an archive that starts polling a feed today
     * collects articles published last week. An operator browsing the
     * archive means the first; a reader walking a timeline means the
     * second.
     */
    @Nullable Instant publishedSince;

    /** Upper bound on {@code publishedAt}, exclusive. Pairs with {@link #publishedSince}. */
    @Nullable Instant publishedUntil;

    /** Upper bound on {@code firstSeenAt}, exclusive. */
    @Nullable Instant until;

    /** Ascending by {@code firstSeenAt} instead of the default descending. */
    boolean oldestFirst;
}
