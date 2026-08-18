package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.util.Hashes;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds the stored article from a candidate, its source and a resolved
 * language.
 *
 * <p>Pure and static: given the same inputs it produces the same document,
 * which is what lets the interesting parts — the dedup key, the content
 * fingerprint, category merging — be tested without a database.
 */
public final class ArticleFactory {

    private ArticleFactory() {
    }

    /** SHA-256 of the normalised URL. */
    public static String dedupKey(String normalizedUrl) {
        return Hashes.sha256(normalizedUrl);
    }

    /**
     * Fingerprint over title and teaser, for finding the same story
     * republished at a different URL.
     *
     * <p>Case-folded and whitespace-collapsed so that trivial editorial
     * differences do not produce a different fingerprint, but otherwise
     * exact — this is a grouping handle, not a similarity measure, and
     * pretending otherwise would invite treating it as one.
     */
    public static String contentHash(String title, String summary) {
        String normalized = (TextCleaner.normalizeWhitespace(title) + "\n"
                + TextCleaner.normalizeWhitespace(summary)).toLowerCase(Locale.ROOT);
        return Hashes.sha256(normalized);
    }

    /**
     * Assembles the document to insert.
     *
     * @param contentStatus initial body state — {@code PENDING} when the
     *                      content worker should fetch the article,
     *                      {@code SKIPPED} when it should not
     */
    public static ArticleDocument build(ArticleCandidate candidate, SourceDocument source,
            LanguageResolver.Resolution language, ContentStatus contentStatus, Instant now) {

        return ArticleDocument.builder()
                .dedupKey(dedupKey(candidate.getUrl()))
                .url(candidate.getUrl())
                .originalUrl(candidate.getOriginalUrl())
                .contentHash(contentHash(candidate.getTitle(),
                        StringUtils.defaultString(candidate.getSummary())))
                .title(candidate.getTitle())
                .summary(candidate.getSummary())
                .author(candidate.getAuthor())
                .imageUrl(candidate.getImageUrl())
                .guid(candidate.getGuid())
                .language(language.language())
                .languageSource(language.source())
                .categories(mergeCategories(source.getCategories(), candidate.getCategories()))
                .sourceNames(new ArrayList<>(List.of(source.getName())))
                .firstSourceName(source.getName())
                .publishedAt(candidate.getPublishedAt())
                .firstSeenAt(now)
                .lastSourceAddedAt(now)
                .contentStatus(contentStatus)
                // Only a PENDING article belongs in the content queue. Giving
                // a SKIPPED one an attempt time would put it in the partial
                // index for a worker that will never claim it.
                .contentNextAttemptAt(contentStatus == ContentStatus.PENDING ? now : null)
                .build();
    }

    /**
     * Source categories first, then the entry's own, deduplicated and
     * order-preserving.
     *
     * <p>Kept verbatim. Publishers disagree completely about what a category
     * is — some emit sections, some emit tags, some emit both in one field —
     * and folding them into a taxonomy of ours at ingest would destroy
     * information no later step could recover.
     */
    static List<String> mergeCategories(List<String> sourceCategories,
            List<String> entryCategories) {

        Set<String> merged = new LinkedHashSet<>();
        for (String value : sourceCategories) {
            if (StringUtils.isNotBlank(value)) {
                merged.add(value.trim());
            }
        }
        for (String value : entryCategories) {
            if (StringUtils.isNotBlank(value)) {
                merged.add(value.trim());
            }
        }
        return new ArrayList<>(merged);
    }
}
