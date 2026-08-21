package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterOutcome;
import de.mhus.hrafnagud.api.filter.FilterOutcomes;
import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.settings.TestSettings;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * What re-evaluating one article writes.
 *
 * <p>The update is inspected rather than a database, because the decisions
 * being pinned are all about <em>which fields are touched</em>: the record is
 * always written, the queues move only on a real flip, and a finished or
 * hand-skipped article is left alone. Those are exactly the properties that
 * would rot silently.
 */
class ArticleServicePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private MongoTemplate mongoTemplate;
    private ArticleService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        // No pivot language, which is this instance's real default and the
        // state the archive has been collected in so far.
        service = serviceWith("");
    }

    private ArticleService serviceWith(String pivotLanguage) {
        HuginProperties properties = new HuginProperties();
        properties.getTranslation().setPivotLanguage(pivotLanguage);
        return new ArticleService(
                mock(ArticleRepository.class),
                mock(ArticleContentRepository.class),
                mock(EnrichmentService.class),
                mongoTemplate,
                new de.mhus.hrafnagud.munin.place.PlaceRegistry(),
                mock(de.mhus.hrafnagud.munin.category.CategoryMappingService.class),
                mock(de.mhus.hrafnagud.munin.filter.ArticleFilterService.class),
                TestSettings.of(properties));
    }

    private static FilterOutcomes translation(FilterDecision decision, String rule) {
        return new FilterOutcomes(FilterOutcome.defaultAccept(),
                FilterOutcome.of(decision, rule));
    }

    private Document capturedSet() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(),
                eq(ArticleDocument.class));
        return (Document) captor.getValue().getUpdateObject().get("$set");
    }

    private Document capturedUnset() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(),
                eq(ArticleDocument.class));
        return (Document) captor.getValue().getUpdateObject().get("$unset");
    }

    /**
     * The finding this test exists for: an accept rule produces {@code ACCEPT},
     * which is also the default, so recording only on a change made a rescued
     * article indistinguishable from one nothing matched.
     */
    @Test
    void the_deciding_rule_is_recorded_even_when_the_decision_did_not_change() {
        // Both policies set explicitly, as a document read from MongoDB has
        // them: @Builder ignores field initialisers, so a builder-made document
        // carries null where a loaded one carries the default.
        ArticleDocument article = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.ACCEPT)
                .translationStatus(TranslationStatus.SKIPPED)
                .contentStatus(ContentStatus.SKIPPED)
                .build();

        ArticleService.PolicyUpdate update = service.applyPolicy(article,
                translation(FilterDecision.ACCEPT, "accept-topic-medtop-15000000"), NOW);

        assertThat(update.decisionChanged()).isFalse();
        assertThat(capturedSet())
                .containsEntry("translationPolicyRule", "accept-topic-medtop-15000000")
                .containsEntry("policyAt", NOW);
    }

    @Test
    void a_newly_denied_article_leaves_the_queue() {
        ArticleDocument article = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.ACCEPT)
                .translationStatus(TranslationStatus.PENDING)
                .language("en")
                .contentStatus(ContentStatus.SKIPPED)
                .build();

        ArticleService.PolicyUpdate update = serviceWith("de").applyPolicy(article,
                translation(FilterDecision.DENY, "deny-language-en"), NOW);

        assertThat(update.queuedOut()).isTrue();
        assertThat(capturedSet())
                .containsEntry("translationStatus", TranslationStatus.SKIPPED);
        // Unset, not just changed: that is what takes it out of the partial
        // index rather than only out of the query.
        assertThat(capturedUnset()).containsKey("translationNextAttemptAt");
    }

    /**
     * Found by a live run: lifting a deny rule put four thousand articles into
     * a queue nothing could have worked, because this path set {@code PENDING}
     * on its own instead of asking the function ingest uses. With no pivot
     * language configured there is nothing to translate into, so the article
     * stays skipped — the decision changed, the queue did not.
     */
    @Test
    void accepting_an_article_does_not_queue_it_when_translation_is_off() {
        ArticleDocument article = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.DENY)
                .translationStatus(TranslationStatus.SKIPPED)
                .language("en")
                .contentStatus(ContentStatus.SKIPPED)
                .build();

        // MuninProperties defaults leave translation.pivotLanguage empty.
        ArticleService.PolicyUpdate update = service.applyPolicy(article,
                translation(FilterDecision.ACCEPT, null), NOW);

        assertThat(update.decisionChanged()).isTrue();
        assertThat(update.queuedIn()).isFalse();
        assertThat(capturedSet())
                .containsEntry("translationPolicy", FilterDecision.ACCEPT)
                .doesNotContainKey("translationStatus");
    }

    /** Nor when the article is already in the pivot language. */
    @Test
    void accepting_an_article_already_in_the_pivot_language_does_not_queue_it() {
        ArticleService withPivot = serviceWith("de");

        ArticleDocument german = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.DENY)
                .translationStatus(TranslationStatus.SKIPPED)
                .language("de")
                .contentStatus(ContentStatus.SKIPPED)
                .build();

        assertThat(withPivot.applyPolicy(german, translation(FilterDecision.ACCEPT, null), NOW)
                .queuedIn()).isFalse();

        ArticleDocument english = ArticleDocument.builder()
                .id("a2")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.DENY)
                .translationStatus(TranslationStatus.SKIPPED)
                .language("en")
                .contentStatus(ContentStatus.SKIPPED)
                .build();

        assertThat(withPivot.applyPolicy(english, translation(FilterDecision.ACCEPT, null), NOW)
                .queuedIn()).isTrue();
    }

    @Test
    void a_newly_accepted_article_returns_with_a_fresh_budget() {
        ArticleDocument article = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.DENY)
                .translationStatus(TranslationStatus.SKIPPED)
                .translationAttempts(3)
                .language("en")
                .contentStatus(ContentStatus.SKIPPED)
                .build();

        ArticleService.PolicyUpdate update = serviceWith("de").applyPolicy(article,
                translation(FilterDecision.ACCEPT, null), NOW);

        assertThat(update.queuedIn()).isTrue();
        assertThat(capturedSet())
                .containsEntry("translationStatus", TranslationStatus.PENDING)
                .containsEntry("translationNextAttemptAt", NOW)
                // What stopped it was a rule, not the article: it has not used
                // any attempts on its own behalf.
                .containsEntry("translationAttempts", 0);
    }

    @Test
    void a_finished_translation_is_never_requeued_or_undone() {
        ArticleDocument article = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .translationPolicy(FilterDecision.ACCEPT)
                .translationStatus(TranslationStatus.DONE)
                .contentStatus(ContentStatus.FETCHED)
                .build();

        ArticleService.PolicyUpdate update = service.applyPolicy(article,
                translation(FilterDecision.DENY, "deny-language-en"), NOW);

        assertThat(update.decisionChanged()).isTrue();
        assertThat(update.queuedOut()).isFalse();
        // The decision is recorded; the translation that exists stays.
        assertThat(capturedSet())
                .containsEntry("translationPolicy", FilterDecision.DENY)
                .doesNotContainKey("translationStatus");
    }

    /**
     * A body taken out by hand carries {@code ACCEPT} — the same value a run
     * that finds nothing produces — so an unchanged decision must not resurrect
     * it. This is why the queue moves on a flip rather than on a status.
     */
    @Test
    void a_hand_skipped_body_survives_a_run_that_changes_nothing() {
        ArticleDocument article = ArticleDocument.builder()
                .id("a1")
                .contentPolicy(FilterDecision.ACCEPT)
                .contentStatus(ContentStatus.SKIPPED)
                .translationPolicy(FilterDecision.ACCEPT)
                .translationStatus(TranslationStatus.SKIPPED)
                .build();

        ArticleService.PolicyUpdate update =
                service.applyPolicy(article, FilterOutcomes.unfiltered(), NOW);

        assertThat(update.decisionChanged()).isFalse();
        assertThat(capturedSet()).doesNotContainKey("contentStatus");
    }
}
