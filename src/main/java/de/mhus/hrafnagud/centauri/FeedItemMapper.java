package de.mhus.hrafnagud.centauri;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
import de.mhus.vance.ode.centauri.OdeItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Turns a stored article into one entry of the feed.
 *
 * <h2>A translated article is served translated</h2>
 *
 * <p>When a translation exists, its title and teaser take the visible
 * fields and {@code language} names the language the reader is actually
 * looking at. That is the point of the pivot language: everything
 * downstream reads one language, and a reader that has to notice which
 * entries were translated has been handed the archive's internals.
 *
 * <p>The original is not thrown away — it moves to {@code extras} under
 * {@code originalTitle} / {@code originalLanguage}, together with the model
 * that produced the translation. A reader that wants to show provenance
 * can; one that does not, need not know.
 *
 * <p>An article still waiting for its translation is served as it is, in
 * its own language. Withholding it until translated would be worse: a news
 * archive whose newest entries are invisible for as long as a backlog
 * takes is not a news archive.
 */
final class FeedItemMapper {

    private FeedItemMapper() {}

    static OdeItem toItem(ArticleDocument article, @Nullable EnrichmentDocument translation,
                          PlaceRegistry places) {
        String title = article.getTitle();
        String summary = article.getSummary();
        String language = article.getLanguage();

        Map<String, Object> extras = new LinkedHashMap<>();
        if (translation != null) {
            String translatedTitle = text(translation, "title");
            if (StringUtils.isNotBlank(translatedTitle)) {
                extras.put("originalTitle", title);
                if (StringUtils.isNotBlank(language)) {
                    extras.put("originalLanguage", language);
                }
                title = translatedTitle;
                summary = StringUtils.defaultIfBlank(text(translation, "summary"), summary);
                language = StringUtils.defaultIfBlank(translation.getLanguage(), language);
                if (StringUtils.isNotBlank(translation.getModel())) {
                    extras.put("translationModel", translation.getModel());
                }
            }
        }
        // Where the publisher sits, as a name rather than an id.
        //
        // In `extras` and not as a facet value on the item: a facet is what a
        // reader filters by, and it resolves ids against the tree it was
        // handed with the declaration. This is the other thing — provenance to
        // read on the entry itself — and a reader showing „iso:DE" to a person
        // would be showing them our storage format.
        String origin = article.getOriginCountry();
        if (StringUtils.isNotBlank(origin)) {
            extras.put("originCountry", origin);
            places.forCountry(origin)
                    .ifPresent(place -> extras.put("originPlace", place.name()));
        }
        if (!article.getSourceNames().isEmpty()) {
            // Which feeds delivered this — plural, because deduplication
            // merges the same article arriving from several of them.
            extras.put("sources", List.copyOf(article.getSourceNames()));
        }

        return new OdeItem(
                article.getId(),
                // The resume token for exactly this entry.
                //
                // Not optional for this source, and the failure without it is
                // silent: we page by (publishedAt, id) — timestamps are not
                // unique in a news archive — so a reader that fell back to the
                // bare item id would hand us something FeedCursor cannot parse,
                // we would read that as "start at the beginning", and the reader's
                // scroll would serve the same page forever. The reader cuts a
                // merged page in the middle far more often than it consumes a
                // whole batch, so this is the common path, not the corner.
                FeedCursor.encode(article.getPublishedAt(), article.getId()),
                article.getPublishedAt(),
                title,
                article.getUrl(),
                StringUtils.trimToNull(summary),
                // Bodies are a separate fetch and a separate collection —
                // see HrafnagudFeedSource#body and carriesFullBody().
                null,
                StringUtils.trimToNull(article.getAuthor()),
                StringUtils.trimToNull(language),
                StringUtils.trimToNull(article.getImageUrl()),
                // No control URL: this service has no UI to send a reader to.
                null,
                List.copyOf(article.getCategories()),
                Map.copyOf(extras));
    }

    private static String text(EnrichmentDocument enrichment, String key) {
        Object value = enrichment.getContent() == null ? null : enrichment.getContent().get(key);
        return value instanceof String s ? s.trim() : "";
    }

    /**
     * The teaser plus everything a single-entry lookup can afford.
     *
     * <p>Built on top of the page entry rather than beside it, so the two can
     * never disagree about the fields they share — the detail is the same
     * record with more filled in, which is exactly what the contract promises.
     *
     * <p>What is added: the fetched body, the article's verbatim publisher
     * categories, the place path and the normalised topic path. The last two
     * travel as ids in {@code extras} and not as facet values, because a facet
     * is a filter and this is provenance to read; the human-readable place
     * name is already on the teaser.
     */
    static OdeItem withDetail(OdeItem teaser, ArticleDocument article, @Nullable String body) {
        Map<String, Object> extras = new LinkedHashMap<>(teaser.extras());
        if (!article.getCategories().isEmpty()) {
            // The publisher's own words, kept verbatim. Not a filter surface —
            // 7,365 distinct strings across the archive — but the most direct
            // statement about the article there is.
            extras.put("categories", List.copyOf(article.getCategories()));
        }
        if (!article.getOriginPlaceIds().isEmpty()) {
            extras.put("originPlaceIds", List.copyOf(article.getOriginPlaceIds()));
        }
        if (!article.getTopicIds().isEmpty()) {
            extras.put("topicIds", List.copyOf(article.getTopicIds()));
        }
        if (!article.getSourceNames().isEmpty()) {
            extras.put("sources", List.copyOf(article.getSourceNames()));
        }
        if (article.getFirstSeenAt() != null) {
            extras.put("collectedAt", article.getFirstSeenAt().toString());
        }
        if (article.getContentWordCount() > 0) {
            extras.put("wordCount", article.getContentWordCount());
        }
        return new OdeItem(
                teaser.id(), teaser.cursor(), teaser.publishedAt(), teaser.title(),
                teaser.url(), teaser.summary(), body, teaser.author(), teaser.language(),
                teaser.imageUrl(), teaser.controlUrl(), teaser.tags(), Map.copyOf(extras));
    }
}
