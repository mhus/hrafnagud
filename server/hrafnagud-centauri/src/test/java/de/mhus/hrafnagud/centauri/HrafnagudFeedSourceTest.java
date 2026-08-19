package de.mhus.hrafnagud.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleCursor;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.source.SourceService;
import de.mhus.vance.ode.centauri.OdeDirection;
import de.mhus.vance.ode.centauri.OdeItem;
import de.mhus.vance.ode.centauri.OdeItemPage;
import de.mhus.vance.ode.centauri.OdeItemQuery;
import de.mhus.vance.ode.centauri.OdeSelector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The feed source's side of the Centauri contract: what it declares it can
 * do, how it pages, and how a translated article is presented.
 *
 * <p>Munin is mocked. What is worth pinning here is not that Mongo returns
 * rows but the decisions this class makes on top of them — the ones a
 * reader would notice being wrong and the archive would not.
 */
class HrafnagudFeedSourceTest {

    private static final Instant T2 = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-19T09:00:00Z");

    private ArticleService articles;
    private EnrichmentService enrichments;
    private SourceService sources;
    private HrafnagudFeedSource feed;

    @BeforeEach
    void setUp() {
        articles = mock(ArticleService.class);
        enrichments = mock(EnrichmentService.class);
        sources = mock(SourceService.class);
        when(enrichments.latestForEach(any(), any())).thenReturn(Map.of());
        feed = new HrafnagudFeedSource(articles, enrichments, sources);
    }

    // ──────────────────── capabilities ────────────────────

    @Test
    void language_pushdown_is_not_claimed_because_a_translated_entry_changes_language() {
        // The article's stored language is the original; what the reader is
        // shown may be the pivot. Pushing the filter down would filter one
        // and display the other.
        assertThat(feed.capabilities().pushdownLanguage()).isFalse();
    }

    @Test
    void the_since_bound_and_the_ordering_use_the_same_key() {
        // Claiming `since` while ordering by a different timestamp is how a
        // page ends up filtered on one axis and sorted on another.
        assertThat(feed.capabilities().pushdownSince()).isTrue();
        assertThat(feed.capabilities().supportsNewerDirection()).isTrue();
    }

    @Test
    void no_signals_are_accepted_so_the_reader_hides_the_buttons() {
        // Accepting a report and dropping it is worse than refusing it: the
        // reader would offer a control that does nothing.
        assertThat(feed.capabilities().signalsAccepted()).isEmpty();
        assertThat(feed.capabilities().carriesControlUrl()).isFalse();
    }

    @Test
    void bodies_are_declared_absent_from_the_listing() {
        assertThat(feed.capabilities().carriesFullBody()).isFalse();
    }

    // ──────────────────── selectors ────────────────────

    @Test
    void selectors_offer_the_whole_archive_and_one_stream_per_enabled_source() {
        when(sources.list(eq(true), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(source("bbc-world", "BBC World", "en")));

        List<OdeSelector> selectors = feed.selectors();

        assertThat(selectors).extracting(OdeSelector::value)
                .containsExactly("all", "source:bbc-world");
        assertThat(selectors.get(1).label()).isEqualTo("BBC World");
        assertThat(selectors.get(1).language()).isEqualTo("en");
    }

    @Test
    void only_enabled_sources_become_selectable() {
        feed.selectors();
        // A disabled feed still has articles in the archive, but offering it
        // as a stream promises updates that will not come.
        verify(sources).list(eq(true), any(), any(), any(), anyInt(), anyInt());
    }

    // ──────────────────── paging ────────────────────

    @Test
    void a_full_page_carries_a_cursor_built_from_its_last_entry() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(
                        article("a1", T2, "Newer"),
                        article("a2", T1, "Older"),
                        article("a3", T1, "One too many"))));

        OdeItemPage page = feed.items(query("all", null, OdeDirection.OLDER, 2));

        assertThat(page.items()).extracting(OdeItem::id).containsExactly("a1", "a2");
        assertThat(page.hasMore()).isTrue();
        // The cursor names the last entry actually delivered, timestamp and
        // id both — a timestamp alone would repeat or skip its siblings.
        assertThat(page.nextCursor()).isEqualTo(T1 + "|a2");
    }

    @Test
    void the_last_page_has_no_cursor() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(article("a1", T2, "Only"))));

        OdeItemPage page = feed.items(query("all", null, OdeDirection.OLDER, 10));

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void one_row_beyond_the_page_is_fetched_so_hasMore_needs_no_count() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>());

        feed.items(query("all", null, OdeDirection.OLDER, 20));

        verify(articles).pageByPublished(any(), any(), anyBoolean(), eq(21));
    }

    @Test
    void a_cursor_is_decoded_and_the_direction_reaches_the_query() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>());

        feed.items(query("all", T1 + "|a7", OdeDirection.NEWER, 5));

        ArgumentCaptor<ArticleCursor> cursor = ArgumentCaptor.forClass(ArticleCursor.class);
        verify(articles).pageByPublished(any(), cursor.capture(), eq(true), anyInt());
        assertThat(cursor.getValue().publishedAt()).isEqualTo(T1);
        assertThat(cursor.getValue().articleId()).isEqualTo("a7");
    }

    @Test
    void a_source_selector_becomes_a_source_filter() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>());

        feed.items(query("source:bbc-world", null, OdeDirection.OLDER, 5));

        ArgumentCaptor<ArticleQuery> filter = ArgumentCaptor.forClass(ArticleQuery.class);
        verify(articles).pageByPublished(filter.capture(), any(), anyBoolean(), anyInt());
        assertThat(filter.getValue().getSourceName()).isEqualTo("bbc-world");
    }

    @Test
    void the_all_selector_filters_by_nothing() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>());

        feed.items(query("all", null, OdeDirection.OLDER, 5));

        ArgumentCaptor<ArticleQuery> filter = ArgumentCaptor.forClass(ArticleQuery.class);
        verify(articles).pageByPublished(filter.capture(), any(), anyBoolean(), anyInt());
        assertThat(filter.getValue().getSourceName()).isNull();
    }

    @Test
    void a_selector_outside_the_grammar_is_an_empty_stream_not_a_failure() {
        OdeItemPage page = feed.items(query("category:tech", null, OdeDirection.OLDER, 5));

        // A reader holding a selector this version does not understand
        // should see an empty timeline, not a broken source — and it costs
        // no query to say so.
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        verify(articles, never()).pageByPublished(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void a_selector_for_a_deleted_source_is_answered_by_the_query_not_by_a_lookup() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>());

        OdeItemPage page = feed.items(query("source:deleted-yesterday", null,
                OdeDirection.OLDER, 5));

        // Deliberately no existence check: a source that is gone has no
        // articles, so the filter already returns nothing. Verifying the
        // registry first would cost a lookup on every page turn to produce
        // the same empty answer.
        assertThat(page.items()).isEmpty();
        verify(articles).pageByPublished(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void the_since_bound_is_passed_as_a_published_bound_not_a_collected_one() {
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>());

        feed.items(new OdeItemQuery("all", null, OdeDirection.OLDER, 5,
                null, Set.of(), T1, null, null));

        ArgumentCaptor<ArticleQuery> filter = ArgumentCaptor.forClass(ArticleQuery.class);
        verify(articles).pageByPublished(filter.capture(), any(), anyBoolean(), anyInt());
        assertThat(filter.getValue().getPublishedSince()).isEqualTo(T1);
        // `since` on the archive means "collected since", which is a
        // different question and would give a different answer.
        assertThat(filter.getValue().getSince()).isNull();
    }

    // ──────────────────── translation ────────────────────

    @Test
    void a_translated_article_is_served_in_the_pivot_language() {
        ArticleDocument article = article("a1", T2, "Council approves plan");
        article.setSummary("The vote ended a debate.");
        article.setLanguage("en");
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(article)));
        when(enrichments.latestForEach(any(), eq(EnrichmentType.TRANSLATION)))
                .thenReturn(Map.of("a1", translation("Rat beschliesst Plan",
                        "Die Abstimmung beendete eine Debatte.", "de", "openai:x")));

        OdeItem item = feed.items(query("all", null, OdeDirection.OLDER, 5)).items().get(0);

        assertThat(item.title()).isEqualTo("Rat beschliesst Plan");
        assertThat(item.summary()).isEqualTo("Die Abstimmung beendete eine Debatte.");
        // The reader is looking at German, so that is what `language` says.
        assertThat(item.language()).isEqualTo("de");
        // Nothing is lost — a reader that wants provenance can show it.
        assertThat(item.extras())
                .containsEntry("originalTitle", "Council approves plan")
                .containsEntry("originalLanguage", "en")
                .containsEntry("translationModel", "openai:x");
    }

    @Test
    void an_untranslated_article_is_served_as_it_is() {
        ArticleDocument article = article("a1", T2, "Council approves plan");
        article.setLanguage("en");
        when(articles.pageByPublished(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(article)));

        OdeItem item = feed.items(query("all", null, OdeDirection.OLDER, 5)).items().get(0);

        // Withholding it until the backlog clears would make the newest
        // entries of a news archive the invisible ones.
        assertThat(item.title()).isEqualTo("Council approves plan");
        assertThat(item.language()).isEqualTo("en");
        assertThat(item.extras()).doesNotContainKey("originalTitle");
    }

    // ──────────────────── body ────────────────────

    @Test
    void a_body_that_has_not_been_fetched_is_absent() {
        when(articles.findContent("a1")).thenReturn(Optional.empty());

        // Absent, not empty: an unfetched body is the normal state of a
        // fresh entry, and the contract turns that into a 404.
        assertThat(feed.body("a1", null)).isEmpty();
    }

    // ──────────────────── fixtures ────────────────────

    private static OdeItemQuery query(String selector, String cursor,
            OdeDirection direction, int limit) {
        // Trailing nulls: no reader pseudonym and no authenticated caller. The
        // archive serves both the same way — it authenticates with the static
        // api-key and does not publish an OdeAuthService.
        return new OdeItemQuery(selector, cursor, direction, limit,
                null, Set.of(), null, null, null);
    }

    private static ArticleDocument article(String id, Instant publishedAt, String title) {
        ArticleDocument article = new ArticleDocument();
        article.setId(id);
        article.setTitle(title);
        article.setUrl("https://example.test/" + id);
        article.setPublishedAt(publishedAt);
        return article;
    }

    private static SourceDocument source(String name, String title, String language) {
        SourceDocument source = new SourceDocument();
        source.setName(name);
        source.setTitle(title);
        source.setLanguage(language);
        return source;
    }

    private static EnrichmentDocument translation(String title, String summary,
            String language, String model) {
        return EnrichmentDocument.builder()
                .articleId("a1")
                .type(EnrichmentType.TRANSLATION)
                .producer("test")
                .model(model)
                .language(language)
                .content(Map.of("title", title, "summary", summary))
                .build();
    }
}
