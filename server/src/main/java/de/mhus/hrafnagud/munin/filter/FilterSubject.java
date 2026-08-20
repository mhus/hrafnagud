package de.mhus.hrafnagud.munin.filter;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The facts about one article that rules are allowed to look at.
 *
 * <p>Explicit, and free of {@code ArticleDocument} on purpose. The two callers
 * have different things in hand — ingest holds a candidate plus its source and
 * has written nothing yet, re-evaluation holds a stored document — so the
 * mapping belongs to them, and this record is what makes both produce the same
 * input. It also lets the evaluator be tested without a database or a feed.
 *
 * @param profile the source's fetch profile — a polling cadence class, not a
 *                genre; null when the source names none
 */
public record FilterSubject(
        String url,
        String host,
        List<String> sourceNames,
        @Nullable String language,
        List<String> originPlaceIds,
        List<String> categories,
        List<String> topicIds,
        @Nullable String profile) {

    public FilterSubject {
        sourceNames = List.copyOf(sourceNames);
        originPlaceIds = List.copyOf(originPlaceIds);
        categories = List.copyOf(categories);
        topicIds = List.copyOf(topicIds);
    }

    /** The same, deriving the host from the URL. */
    public static FilterSubject of(String url, List<String> sourceNames,
            @Nullable String language, List<String> originPlaceIds, List<String> categories,
            List<String> topicIds, @Nullable String profile) {

        return new FilterSubject(url, hostOf(url), sourceNames, language, originPlaceIds,
                categories, topicIds, profile);
    }

    /**
     * The host, lowercased and without a leading {@code www.}.
     *
     * <p>Dropping {@code www.} means one rule covers both spellings of the same
     * publisher. A URL that cannot be parsed yields an empty host rather than an
     * exception: that is a fact about the article, not a reason to fail its
     * ingest.
     */
    public static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
