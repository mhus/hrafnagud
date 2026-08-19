package de.mhus.hrafnagud.zarniwoop;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.vance.ode.zarniwoop.OdeContentBody;
import de.mhus.vance.ode.zarniwoop.OdeContentInline;
import de.mhus.vance.ode.zarniwoop.OdeHitContent;
import de.mhus.vance.ode.zarniwoop.OdeSearchCapabilities;
import de.mhus.vance.ode.zarniwoop.OdeSearchDomain;
import de.mhus.vance.ode.zarniwoop.OdeSearchHit;
import de.mhus.vance.ode.zarniwoop.OdeSearchModality;
import de.mhus.vance.ode.zarniwoop.OdeSearchQuery;
import de.mhus.vance.ode.zarniwoop.OdeSearchResponse;
import de.mhus.vance.ode.zarniwoop.OdeSearchTier;
import de.mhus.vance.ode.zarniwoop.SearchSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * The archive, answering Vancetope research queries.
 *
 * <p>The other half of {@code hrafnagud-centauri}, over the same data and
 * with the opposite question. Centauri hands out a timeline: chronological,
 * cursored, endless, read by a person scrolling. This hands out an answer:
 * ranked by relevance, one shot, read by a model inside a research turn that
 * somebody is waiting on.
 *
 * <p>Read-only, like its sibling. Nothing here writes.
 */
@Slf4j
@RequiredArgsConstructor
public class HrafnagudSearchSource implements SearchSource {

    /**
     * Ceiling on hits per query. Small on purpose: these go into an LLM's
     * context, where the twentieth hit costs tokens and adds nothing that
     * the first twenty did not.
     */
    private static final int MAX_RESULTS = 25;

    /** How long Vancetope may hold {@link #capabilities()}. It is a constant. */
    private static final Duration CAPABILITIES_TTL = Duration.ofHours(1);

    /** Cap on a body handed back through {@link #content}. */
    private static final int MAX_BODY_CHARS = 200_000;

    private final ArticleService articles;
    private final EnrichmentService enrichments;

    @Override
    public OdeSearchCapabilities capabilities() {
        return new OdeSearchCapabilities(
                // One modality, honestly. A news archive answering an
                // ACADEMIC or ENCYCLOPEDIA query would be answering a
                // question it was not asked.
                Set.of(OdeSearchModality.NEWS),
                Set.of(OdeSearchDomain.NEWS),
                // EXPERT because there is a real filter vocabulary to act on
                // — declaring it without one is what the contract warns
                // against.
                Set.of(OdeSearchTier.NORMAL, OdeSearchTier.EXPERT),
                MAX_RESULTS,
                Set.of(ExpertParams.SOURCE, ExpertParams.ORIGINAL_LANGUAGE,
                        ExpertParams.CATEGORY, ExpertParams.SINCE, ExpertParams.UNTIL),
                // Bodies are fetched on their own schedule into their own
                // collection, so they are served on demand rather than
                // shipped with every hit.
                true,
                CAPABILITIES_TTL);
    }

    @Override
    public OdeSearchResponse search(OdeSearchQuery query) {
        ArticleQuery.ArticleQueryBuilder filter = ArticleQuery.builder()
                .text(query.query())
                .sourceName(string(query, ExpertParams.SOURCE))
                .language(string(query, ExpertParams.ORIGINAL_LANGUAGE))
                .category(string(query, ExpertParams.CATEGORY))
                .publishedSince(instant(query, ExpertParams.SINCE))
                // publishedUntil, not until: the caller means "published
                // before", and `until` bounds when the archive collected it.
                .publishedUntil(instant(query, ExpertParams.UNTIL));

        List<ArticleDocument> found;
        try {
            found = articles.searchByRelevance(
                    filter.build(), query.locale(), query.maxResults(),
                    // Bodies matter here in a way they do not for a timeline:
                    // a research query is often about something mentioned in
                    // the article rather than announced in its headline.
                    true);
        } catch (RuntimeException e) {
            // Throwing marks this source broken and stops Vancetope asking
            // for minutes. Right for an index that is down, wrong for a
            // query the archive simply could not run — so only the former
            // propagates.
            log.warn("Search failed for '{}': {}", query.query(), e.toString());
            throw e;
        }
        if (found.isEmpty()) {
            // An empty result is not an exception. Saying so keeps this
            // source in rotation for the next question.
            return OdeSearchResponse.empty("no article in the archive matches");
        }

        Map<String, EnrichmentDocument> translations = enrichments.latestForEach(
                found.stream().map(ArticleDocument::getId).toList(),
                EnrichmentType.TRANSLATION);

        return OdeSearchResponse.of(found.stream()
                .map(a -> toHit(a, translations.get(a.getId())))
                .toList());
    }

    /**
     * The extracted body of one article.
     *
     * <p>Absent when nothing has been fetched yet, which the contract turns
     * into a 404 — a legitimate answer here rather than an error, because
     * body fetching is opt-in and asynchronous. {@link #bodyOffer} only
     * promises a body for articles that have one, so a 404 on this path
     * means the article lost its content between the search and the fetch.
     *
     * <p>Always {@code text/plain}: what is stored is extracted prose, not
     * the original document. The contract carries bytes because a source may
     * serve a PDF; this one never does.
     */
    @Override
    public Optional<OdeContentBody> content(String contentId) {
        return articles.findContent(contentId)
                .map(ArticleContentDocument::getText)
                .filter(StringUtils::isNotBlank)
                .map(text -> new OdeContentBody("text/plain",
                        StringUtils.abbreviate(text, MAX_BODY_CHARS)
                                .getBytes(StandardCharsets.UTF_8)));
    }

    // ──────────────────── mapping ────────────────────

    /**
     * One article as one hit.
     *
     * <p>Presented in the pivot language where a translation exists, the same
     * way the feed does it — a model reading the result should not have to
     * notice which entries the archive happened to translate. The original
     * travels in {@code extras} for a caller that wants to quote it.
     */
    private OdeSearchHit toHit(ArticleDocument article,
            @Nullable EnrichmentDocument translation) {

        String title = article.getTitle();
        String snippet = article.getSummary();
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
                snippet = StringUtils.defaultIfBlank(text(translation, "summary"), snippet);
                language = StringUtils.defaultIfBlank(translation.getLanguage(), language);
            }
        }
        if (article.getPublishedAt() != null) {
            // Recency is not the ranking here, but a model judging a news hit
            // needs to know whether it is reading last week or last year.
            extras.put("publishedAt", article.getPublishedAt().toString());
        }
        if (StringUtils.isNotBlank(language)) {
            extras.put("language", language);
        }
        if (!article.getCategories().isEmpty()) {
            extras.put("categories", List.copyOf(article.getCategories()));
        }
        if (article.getContentWordCount() > 0) {
            // Lets the model decide whether fetching the body is worth it.
            extras.put("bodyWords", article.getContentWordCount());
        }

        return new OdeSearchHit(
                title,
                article.getUrl(),
                StringUtils.trimToNull(snippet),
                // Provenance the model can name in an answer.
                article.getSourceNames().isEmpty() ? null : article.getSourceNames().get(0),
                OdeSearchModality.NEWS,
                bodyOffer(article),
                Map.copyOf(extras));
    }

    /**
     * Offers the body only when there is one.
     *
     * <p>{@code STASH_ON_DEMAND} rather than embedded: an article body is
     * thousands of words, and shipping twenty-five of them with a result list
     * would spend a research turn's context before the model has decided
     * which one it wants. A hit whose body has not been fetched offers
     * nothing at all — better than a promise that resolves to a 404.
     */
    private @Nullable OdeHitContent bodyOffer(ArticleDocument article) {
        if (article.getContentWordCount() <= 0) {
            return null;
        }
        return new OdeHitContent(
                article.getId(),
                "text/plain",
                // Words are counted, bytes are not, and reporting one as the
                // other would be a plausible-looking wrong number. 0 says
                // unknown; the word count travels in extras instead.
                0L,
                OdeContentInline.STASH_ON_DEMAND,
                null);
    }

    // ──────────────────── expert params ────────────────────

    /** The filter vocabulary this source understands. Names are its own. */
    static final class ExpertParams {
        static final String SOURCE = "source";
        /**
         * The language the article was <em>published</em> in.
         *
         * <p>Named for what it filters, which is not what a hit's
         * {@code language} says. With a pivot language configured a translated
         * hit is presented in the pivot and labelled with it, so a caller that
         * saw {@code language: de} on every row and narrowed on {@code language:
         * de} would have removed exactly the translated articles — the filter and
         * the label would have been the same word for two different fields. The
         * pair is {@code originalLanguage} here and {@code originalLanguage} in
         * the hit's extras.
         */
        static final String ORIGINAL_LANGUAGE = "originalLanguage";
        static final String CATEGORY = "category";
        static final String SINCE = "since";
        static final String UNTIL = "until";

        private ExpertParams() {}
    }

    private static @Nullable String string(OdeSearchQuery query, String key) {
        Object value = query.expertParams().get(key);
        return value instanceof String s ? StringUtils.trimToNull(s) : null;
    }

    /**
     * An expert param that is not a parsable instant is <b>ignored</b>, not
     * refused. The contract is explicit about it: the caller cannot know this
     * source's schema, and rejecting one malformed filter costs the whole
     * query.
     */
    private @Nullable Instant instant(OdeSearchQuery query, String key) {
        String raw = string(query, key);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            log.debug("Ignoring unparsable '{}' expert param: {}", key, raw);
            return null;
        }
    }

    private static String text(EnrichmentDocument enrichment, String key) {
        Object value = enrichment.getContent() == null ? null : enrichment.getContent().get(key);
        return value instanceof String s ? s.trim() : "";
    }
}
