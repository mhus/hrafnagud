package de.mhus.hrafnagud.centauri;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleCursor;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.source.SourceService;
import de.mhus.vance.ode.centauri.FeedSource;
import de.mhus.vance.ode.centauri.OdeCapabilities;
import de.mhus.vance.ode.centauri.OdeDirection;
import de.mhus.vance.ode.centauri.OdeItem;
import de.mhus.vance.ode.centauri.OdeItemBody;
import de.mhus.vance.ode.centauri.OdeItemPage;
import de.mhus.vance.ode.centauri.OdeItemQuery;
import de.mhus.vance.ode.centauri.OdeSelector;
import de.mhus.vance.ode.centauri.OdeSelectorKind;
import de.mhus.vance.ode.centauri.OdeSelectorMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * The archive, served to Vancetope as a feed source.
 *
 * <p>Read-only in the strong sense: this class answers questions and
 * changes nothing. Collection, deduplication, body fetching and
 * translation all run on their own schedules whether anybody reads or not,
 * which is why a reader can be added and removed without the archive
 * noticing.
 *
 * <h2>Streams</h2>
 *
 * <p>The selector is {@code all} or {@code source:<name>} — the whole
 * archive, or one feed. Deliberately a small grammar with room in it: it is
 * prefixed so a later {@code category:} or {@code country:} does not have
 * to guess whether {@code tech} is a category or a source that happens to
 * be called that.
 *
 * <h2>Ordering</h2>
 *
 * <p>By {@code publishedAt}, which is what the contract merges on, and
 * <em>not</em> by when this archive collected the article. The two differ
 * whenever a feed is added: everything it carries arrives at once and is
 * weeks old. Ordering by collection time would drop a month of history into
 * a reader's timeline at today's date.
 *
 * <p>The consequence, stated because it is a real one: an article published
 * before a reader's cursor but collected after it sits behind that cursor
 * and is not delivered by a pull-forward. The endless scroll backwards
 * finds it; a refresh does not.
 */
@Slf4j
@RequiredArgsConstructor
public class HrafnagudFeedSource implements FeedSource {

    /** Everything the archive holds, in one stream. */
    static final String SELECTOR_ALL = "all";

    /** One feed, by its registry name. */
    static final String SELECTOR_SOURCE_PREFIX = "source:";

    /**
     * How much of an entry to serve. Well under the operator ceiling in
     * {@code vance.ode.centauri.maxLimit}: the two bound different things —
     * that one what a request may cost, this one what one page of a news
     * archive is worth reading.
     */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * How long a reader may cache the taxonomy. Sources are added by an
     * operator, not by the minute, so half an hour of staleness costs a
     * newly added feed some delay before it can be selected.
     */
    private static final Duration CAPABILITIES_TTL = Duration.ofMinutes(30);

    /** Upper bound on the source list served as selectors. */
    private static final int MAX_SELECTORS = 500;

    private final ArticleService articles;
    private final EnrichmentService enrichments;
    private final SourceService sources;

    @Override
    public OdeCapabilities capabilities() {
        return new OdeCapabilities(
                OdeSelectorMode.ENUMERABLE,
                Set.of(OdeSelectorKind.CATEGORY),
                // Text search: claimed, with one known asymmetry — the index
                // covers the article's OWN title and teaser, so a translated
                // entry is searchable by its original words and not by the
                // ones the reader is looking at ("tariffs" finds it,
                // "Zoelle" does not).
                //
                // Claimed anyway, and the reason it goes the other way than
                // the language pushdown below: declining that one costs
                // nothing, because the reader's own filter reads the field
                // we hand it and lands on the right answer. Declining this
                // one would make the reader page the whole archive to
                // resolve one search. Wrong for the translated minority
                // beats unusable for everyone. Making translations
                // searchable means indexing the enrichment — a real next
                // step, not a subtlety to leave unsaid.
                true,
                // Language: NOT claimed, and not an oversight. With a pivot
                // language configured, a translated entry is served in the
                // pivot and its `language` says so — so the reader filtering
                // on the field it was given lands on the right answer, while
                // a pushdown here would filter the article's original
                // language and quietly disagree with what it displays.
                false,
                // Since: claimed, on publishedAt — the same key this stream
                // is ordered by, so the bound and the order agree.
                true,
                true,
                // Bodies live in their own collection and are fetched on a
                // separate schedule; many entries have none yet.
                false,
                MAX_PAGE_SIZE,
                // No signals: the archive has nowhere to put a report and
                // nothing to do with a clip. Declaring one and dropping it
                // would hide a dead button in the reader's UI.
                Set.of(),
                // No control URL: this service has no UI to link into.
                false,
                CAPABILITIES_TTL);
    }

    @Override
    public List<OdeSelector> selectors() {
        List<OdeSelector> result = new ArrayList<>();
        result.add(new OdeSelector(SELECTOR_ALL, "All sources", OdeSelectorKind.CATEGORY, null));
        for (SourceDocument source : sources.list(true, null, null, null, 0, MAX_SELECTORS)) {
            result.add(new OdeSelector(
                    SELECTOR_SOURCE_PREFIX + source.getName(),
                    StringUtils.defaultIfBlank(source.getTitle(), source.getName()),
                    OdeSelectorKind.CATEGORY,
                    source.getLanguage()));
        }
        return result;
    }

    @Override
    public OdeItemPage items(OdeItemQuery query) {
        String sourceName = sourceOf(query.selector());
        if (sourceName == null && !isAll(query.selector())) {
            // A selector outside the grammar is an empty stream, not an
            // error: a reader may hold one from a version that understood
            // more than this one does, and an empty timeline beats a source
            // that reports itself broken.
            log.debug("Feed: unrecognised selector '{}' — serving an empty page",
                    query.selector());
            return OdeItemPage.empty();
        }
        // A `source:` selector naming a deleted source needs no check — it
        // has no articles, so the filter answers the same question one
        // registry lookup per page turn would.


        boolean ascending = query.direction() == OdeDirection.NEWER;
        ArticleQuery filter = ArticleQuery.builder()
                .sourceName(sourceName)
                .text(query.text())
                .publishedSince(query.since())
                .build();

        // One more than asked for, so hasMore is answered by looking rather
        // than by counting — an unfiltered count over a growing archive is
        // the expensive half of a listing endpoint.
        List<ArticleDocument> found = articles.pageByPublished(
                filter, FeedCursor.decode(query.cursor()), ascending, query.limit() + 1);

        boolean hasMore = found.size() > query.limit();
        List<ArticleDocument> page = hasMore ? found.subList(0, query.limit()) : found;
        if (page.isEmpty()) {
            return OdeItemPage.empty();
        }

        Map<String, EnrichmentDocument> translations = enrichments.latestForEach(
                page.stream().map(ArticleDocument::getId).toList(), EnrichmentType.TRANSLATION);

        List<OdeItem> items = page.stream()
                .map(a -> FeedItemMapper.toItem(a, translations.get(a.getId())))
                .toList();

        ArticleDocument last = page.get(page.size() - 1);
        String nextCursor = hasMore
                ? FeedCursor.encode(last.getPublishedAt(), last.getId())
                : null;
        return new OdeItemPage(items, nextCursor, hasMore);
    }

    @Override
    public Optional<OdeItemBody> body(String itemId, @Nullable String reader) {
        // Absent rather than empty for an article whose body has not been
        // fetched — and the two are genuinely different here. Body fetching
        // is opt-in and asynchronous, so "no text yet" is the normal state
        // of a fresh entry, not a missing entry.
        return articles.findContent(itemId)
                .map(content -> content.getText())
                .filter(StringUtils::isNotBlank)
                .map(OdeItemBody::new);
    }

    // ──────────────────── selectors ────────────────────

    private static boolean isAll(String selector) {
        return selector.isEmpty() || SELECTOR_ALL.equals(selector);
    }

    /** The source name of a {@code source:<name>} selector, else {@code null}. */
    private @Nullable String sourceOf(String selector) {
        if (!selector.startsWith(SELECTOR_SOURCE_PREFIX)) {
            return null;
        }
        String name = selector.substring(SELECTOR_SOURCE_PREFIX.length()).trim();
        return name.isEmpty() ? null : name;
    }
}
