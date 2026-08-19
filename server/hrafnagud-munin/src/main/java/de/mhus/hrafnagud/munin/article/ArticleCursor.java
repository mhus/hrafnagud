package de.mhus.hrafnagud.munin.article;

import java.time.Instant;

/**
 * A position in a stream ordered by {@code publishedAt}.
 *
 * <p>The id is part of it, not decoration. Feeds publish in batches that
 * share a timestamp to the minute or the second, so a cursor carrying only
 * the timestamp has to choose between {@code <} — which skips every sibling
 * of the last row on the page — and {@code <=}, which returns them again.
 * Both are visible to a reader as missing or duplicated entries.
 *
 * @param publishedAt timestamp of the last row of the previous page
 * @param articleId   its id, breaking ties within that timestamp
 */
public record ArticleCursor(Instant publishedAt, String articleId) {

    public ArticleCursor {
        if (publishedAt == null) {
            throw new IllegalArgumentException("cursor publishedAt is required");
        }
        if (articleId == null || articleId.isBlank()) {
            throw new IllegalArgumentException("cursor articleId is required");
        }
    }
}
