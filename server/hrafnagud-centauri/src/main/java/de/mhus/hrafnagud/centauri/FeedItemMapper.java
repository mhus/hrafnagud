package de.mhus.hrafnagud.centauri;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
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

    static OdeItem toItem(ArticleDocument article, @Nullable EnrichmentDocument translation) {
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
        if (!article.getSourceNames().isEmpty()) {
            // Which feeds delivered this — plural, because deduplication
            // merges the same article arriving from several of them.
            extras.put("sources", List.copyOf(article.getSourceNames()));
        }

        return new OdeItem(
                article.getId(),
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
}
