package de.mhus.hrafnagud.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.vance.ode.zarniwoop.OdeContentInline;
import de.mhus.vance.ode.zarniwoop.OdeSearchHit;
import de.mhus.vance.ode.zarniwoop.OdeSearchModality;
import de.mhus.vance.ode.zarniwoop.OdeSearchQuery;
import de.mhus.vance.ode.zarniwoop.OdeSearchResponse;
import de.mhus.vance.ode.zarniwoop.OdeSearchTier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The research provider's side of the Zarniwoop contract.
 *
 * <p>Two rules of that contract are easy to get wrong and expensive when you
 * do — an empty result must not be an exception, and an expert param that
 * makes no sense must be ignored rather than refused. Both are pinned here,
 * along with the mapping decisions a model reading the result would notice.
 */
class HrafnagudSearchSourceTest {

    private static final Instant T = Instant.parse("2026-08-19T10:00:00Z");

    private ArticleService articles;
    private EnrichmentService enrichments;
    private HrafnagudSearchSource source;

    @BeforeEach
    void setUp() {
        articles = mock(ArticleService.class);
        enrichments = mock(EnrichmentService.class);
        when(enrichments.latestForEach(any(), any())).thenReturn(Map.of());
        source = new HrafnagudSearchSource(articles, enrichments);
    }

    // ──────────────────── capabilities ────────────────────

    @Test
    void only_news_is_offered() {
        // A news archive answering an ACADEMIC query would be answering a
        // question it was not asked.
        assertThat(source.capabilities().modalities())
                .containsExactly(OdeSearchModality.NEWS);
    }

    @Test
    void expert_tier_is_declared_because_there_are_real_filters_behind_it() {
        assertThat(source.capabilities().tiers()).contains(OdeSearchTier.EXPERT);
        assertThat(source.capabilities().expertParams())
                .containsExactlyInAnyOrder("source", "language", "category", "since", "until");
    }

    @Test
    void serving_content_is_declared_so_a_stashed_hit_is_not_a_surprise() {
        // bodyOffer hands out STASH_ON_DEMAND; declaring false here would
        // make that look like a bug to the caller.
        assertThat(source.capabilities().servesContent()).isTrue();
    }

    // ──────────────────── the two contract rules ────────────────────

    @Test
    void nothing_found_is_an_empty_response_not_an_exception() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>());

        OdeSearchResponse response = source.search(query("nothing matches this"));

        // Throwing would mark this source broken and stop Vancetope asking
        // for minutes — right for a dead index, wrong for a quiet day.
        assertThat(response.hits()).isEmpty();
        assertThat(response.note()).isNotBlank();
    }

    @Test
    void a_search_that_could_not_run_does_throw() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("index unavailable"));

        // The other half of the same rule: a source that swallows a real
        // failure keeps being asked and keeps answering nothing.
        assertThatThrownBy(() -> source.search(query("anything")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void an_unparsable_expert_param_is_ignored_rather_than_refusing_the_query() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>());

        source.search(new OdeSearchQuery("tariffs", OdeSearchModality.NEWS,
                OdeSearchTier.EXPERT, 10, null, Map.of("since", "last tuesday")));

        // The caller cannot know this source's schema, so a refusal would
        // cost the whole query over one filter it guessed at.
        ArgumentCaptor<ArticleQuery> filter = ArgumentCaptor.forClass(ArticleQuery.class);
        verifyFilter(filter);
        assertThat(filter.getValue().getPublishedSince()).isNull();
        assertThat(filter.getValue().getText()).isEqualTo("tariffs");
    }

    @Test
    void expert_params_reach_the_query() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>());

        source.search(new OdeSearchQuery("tariffs", OdeSearchModality.NEWS,
                OdeSearchTier.EXPERT, 10, null,
                Map.of("source", "bbc-world", "language", "en",
                        "category", "business", "since", "2026-08-01T00:00:00Z")));

        ArgumentCaptor<ArticleQuery> filter = ArgumentCaptor.forClass(ArticleQuery.class);
        verifyFilter(filter);
        ArticleQuery q = filter.getValue();
        assertThat(q.getSourceName()).isEqualTo("bbc-world");
        assertThat(q.getLanguage()).isEqualTo("en");
        assertThat(q.getCategory()).isEqualTo("business");
        // publishedSince, not since: the caller means "published after",
        // while `since` bounds when this archive collected it.
        assertThat(q.getPublishedSince()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(q.getSince()).isNull();
    }

    @Test
    void bodies_are_searched_because_a_research_query_is_often_about_a_mention() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>());

        source.search(query("tariffs"));

        // The feed does not need this; a research caller asking about
        // something mentioned inside an article does.
        org.mockito.Mockito.verify(articles)
                .searchByRelevance(any(), any(), anyInt(), eq(true));
    }

    @Test
    void the_locale_hint_is_passed_on_as_the_stemmer_for_the_query() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>());

        source.search(new OdeSearchQuery("Zölle", OdeSearchModality.NEWS,
                OdeSearchTier.NORMAL, 10, "de-DE", Map.of()));

        verifyLocale("de-DE");
    }

    // ──────────────────── hits ────────────────────

    @Test
    void a_translated_article_is_presented_in_the_pivot_language() {
        ArticleDocument article = article("a1", "Council approves plan", "en");
        article.setSummary("The vote ended a debate.");
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>(List.of(article)));
        when(enrichments.latestForEach(any(), eq(EnrichmentType.TRANSLATION)))
                .thenReturn(Map.of("a1", translation("Rat beschliesst Plan",
                        "Die Abstimmung beendete eine Debatte.", "de")));

        OdeSearchHit hit = source.search(query("plan")).hits().get(0);

        // A model reading the result should not have to notice which entries
        // the archive happened to translate.
        assertThat(hit.title()).isEqualTo("Rat beschliesst Plan");
        assertThat(hit.snippet()).isEqualTo("Die Abstimmung beendete eine Debatte.");
        assertThat(hit.extras())
                .containsEntry("originalTitle", "Council approves plan")
                .containsEntry("originalLanguage", "en")
                .containsEntry("language", "de");
    }

    @Test
    void the_publication_date_travels_with_the_hit() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>(List.of(article("a1", "Title", "en"))));

        // Relevance is the ranking, so a model has no other way to tell
        // whether it is reading last week or last year.
        assertThat(source.search(query("x")).hits().get(0).extras())
                .containsEntry("publishedAt", T.toString());
    }

    @Test
    void an_article_with_a_body_offers_it_on_demand_rather_than_inline() {
        ArticleDocument article = article("a1", "Title", "en");
        article.setContentWordCount(900);
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>(List.of(article)));

        OdeSearchHit hit = source.search(query("x")).hits().get(0);

        // Twenty-five bodies shipped with a result list would spend the
        // research turn's context before the model picks one.
        assertThat(hit.content()).isNotNull();
        assertThat(hit.content().inline()).isEqualTo(OdeContentInline.STASH_ON_DEMAND);
        assertThat(hit.content().contentId()).isEqualTo("a1");
        assertThat(hit.extras()).containsEntry("bodyWords", 900);
    }

    @Test
    void an_article_without_a_body_offers_nothing() {
        when(articles.searchByRelevance(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new ArrayList<>(List.of(article("a1", "Title", "en"))));

        // A promise that resolves to a 404 is worse than no promise.
        assertThat(source.search(query("x")).hits().get(0).content()).isNull();
    }

    // ──────────────────── content ────────────────────

    @Test
    void the_body_comes_back_as_utf8_text() {
        ArticleContentDocument content = new ArticleContentDocument();
        content.setText("Der Rat hat zugestimmt.");
        when(articles.findContent("a1")).thenReturn(Optional.of(content));

        assertThat(source.content("a1")).isPresent();
        assertThat(new String(source.content("a1").orElseThrow().bytes(),
                StandardCharsets.UTF_8)).isEqualTo("Der Rat hat zugestimmt.");
        assertThat(source.content("a1").orElseThrow().mimeType()).isEqualTo("text/plain");
    }

    @Test
    void an_unfetched_body_is_absent_which_the_contract_turns_into_a_404() {
        when(articles.findContent("a1")).thenReturn(Optional.empty());

        assertThat(source.content("a1")).isEmpty();
    }

    // ──────────────────── fixtures ────────────────────

    private void verifyFilter(ArgumentCaptor<ArticleQuery> captor) {
        org.mockito.Mockito.verify(articles)
                .searchByRelevance(captor.capture(), any(), anyInt(), anyBoolean());
    }

    /** Named so it cannot shadow Mockito's static {@code verify}. */
    private void verifyLocale(String expectedLocale) {
        org.mockito.Mockito.verify(articles)
                .searchByRelevance(any(), eq(expectedLocale), anyInt(), anyBoolean());
    }

    private static OdeSearchQuery query(String text) {
        return new OdeSearchQuery(text, OdeSearchModality.NEWS,
                OdeSearchTier.NORMAL, 10, null, Map.of());
    }

    private static ArticleDocument article(String id, String title, String language) {
        ArticleDocument article = new ArticleDocument();
        article.setId(id);
        article.setTitle(title);
        article.setUrl("https://example.test/" + id);
        article.setLanguage(language);
        article.setPublishedAt(T);
        return article;
    }

    private static EnrichmentDocument translation(String title, String summary, String language) {
        return EnrichmentDocument.builder()
                .articleId("a1")
                .type(EnrichmentType.TRANSLATION)
                .producer("test")
                .language(language)
                .content(Map.of("title", title, "summary", summary))
                .build();
    }
}
