package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.api.filter.FilterOutcome;
import de.mhus.hrafnagud.api.filter.FilterOutcomes;
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
import org.jspecify.annotations.Nullable;

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
        return build(candidate, source, language, contentStatus, "", List.of(), now);
    }

    /**
     * Assembles the document to insert, queued for translation.
     *
     * @param pivotLanguage the one language everything is normalised
     *                      into; empty disables translation entirely
     */
    public static ArticleDocument build(ArticleCandidate candidate, SourceDocument source,
            LanguageResolver.Resolution language, ContentStatus contentStatus,
            String pivotLanguage, Instant now) {
        return build(candidate, source, language, contentStatus, pivotLanguage, List.of(), now);
    }

    /**
     * The same, with the publisher's place path resolved.
     *
     * <p>Passed in rather than looked up: this class is static and pure so that
     * what a stored article looks like can be tested without a Spring context,
     * and the hierarchy is a bean. The caller holds both.
     *
     * @param originPlaceIds the source country and everything containing it,
     *                       outermost first; empty when the source names no
     *                       country or names one the table does not know
     */
    public static ArticleDocument build(ArticleCandidate candidate, SourceDocument source,
            LanguageResolver.Resolution language, ContentStatus contentStatus,
            String pivotLanguage, List<String> originPlaceIds, Instant now) {
        return build(candidate, source, language, contentStatus, pivotLanguage,
                originPlaceIds, List.of(), now);
    }

    /**
     * The same, with the normalised topics of the article's categories.
     *
     * @param topicIds Media Topics implied by the categories, with their
     *                 ancestors; empty when none is resolved yet
     */
    public static ArticleDocument build(ArticleCandidate candidate, SourceDocument source,
            LanguageResolver.Resolution language, ContentStatus contentStatus,
            String pivotLanguage, List<String> originPlaceIds, List<String> topicIds,
            Instant now) {

        return build(candidate, source, language, contentStatus, pivotLanguage, originPlaceIds,
                topicIds, FilterOutcomes.unfiltered(), now);
    }

    /**
     * The same, with what the filter rules concluded.
     *
     * <p>A denied article is queued for nothing. Note that the two pipelines
     * are handled asymmetrically here, and deliberately: the caller already
     * computes {@code contentStatus}, so it applies the content decision
     * itself, while the translation status is derived in this class and takes
     * the decision as input.
     *
     * @param filters both decisions plus the rule names, recorded on the
     *                article; {@link FilterOutcomes#unfiltered()} when no rules
     *                were applied
     */
    public static ArticleDocument build(ArticleCandidate candidate, SourceDocument source,
            LanguageResolver.Resolution language, ContentStatus contentStatus,
            String pivotLanguage, List<String> originPlaceIds, List<String> topicIds,
            FilterOutcomes filters, Instant now) {

        TranslationStatus translationStatus = initialTranslationStatus(
                pivotLanguage, language.language(), filters.translation());
        return ArticleDocument.builder()
                .translationStatus(translationStatus)
                .contentPolicy(filters.content().decision())
                .contentPolicyRule(filters.content().rule())
                .translationPolicy(filters.translation().decision())
                .translationPolicyRule(filters.translation().rule())
                .policyAt(now)
                // Only a queued article belongs in the partial index.
                .translationNextAttemptAt(
                        translationStatus == TranslationStatus.PENDING ? now : null)
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
                .topicIds(new ArrayList<>(topicIds))
                .originCountry(source.getCountry())
                .originPlaceIds(new ArrayList<>(originPlaceIds))
                .language(language.language())
                // Derived, never authored: the article's language is the
                // record, this is only what MongoDB's text index is allowed
                // to see. See TextIndexLanguage.
                .textLanguage(TextIndexLanguage.of(language.language()))
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
     * Whether the article needs translating at all.
     *
     * <p>An article already in the pivot language is {@code SKIPPED}, not
     * queued — a model asked to translate German into German can only
     * return what it was given, at full price.
     *
     * <p>An <em>unknown</em> source language is queued. Without knowing
     * what it is we cannot rule it out, and a provider handed text that is
     * already in the target returns it unchanged; the cost of being wrong
     * that way is one call, while skipping wrongly loses the translation
     * silently.
     *
     * <p>A denied article is {@code SKIPPED} whatever its language, and the
     * order matters: the filter is asked first, so a denied article never
     * reaches the language check. That is also why this method is the single
     * place the status is derived — ingest and re-evaluation both call it, and
     * two implementations that agreed on the day they were written would not
     * stay agreed.
     */
    static TranslationStatus initialTranslationStatus(String pivotLanguage,
            @Nullable String articleLanguage, FilterOutcome policy) {

        if (policy.denied()) {
            return TranslationStatus.SKIPPED;
        }
        String pivot = TextCleaner.normalizeLanguage(pivotLanguage);
        if (pivot == null) {
            return TranslationStatus.SKIPPED;
        }
        return pivot.equals(articleLanguage) ? TranslationStatus.SKIPPED : TranslationStatus.PENDING;
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
