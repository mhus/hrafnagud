package de.mhus.hrafnagud.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What the queue does around a provider: what it records, what it
 * retries, and what it refuses to store.
 */
class TranslationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final String ID = "a1";

    private ArticleService articleService;
    private EnrichmentService enrichmentService;
    private MuninProperties properties;

    @BeforeEach
    void setUp() {
        articleService = mock(ArticleService.class);
        enrichmentService = mock(EnrichmentService.class);
        properties = new MuninProperties();
        properties.getTranslation().setPivotLanguage("de");
    }

    private TranslationService serviceWith(@Nullable TranslationProvider provider) {
        return new TranslationService(articleService, enrichmentService, properties,
                objectProviderOf(provider));
    }

    private static ObjectProvider<TranslationProvider> objectProviderOf(
            @Nullable TranslationProvider provider) {
        return new ObjectProvider<>() {
            @Override public TranslationProvider getObject() {
                throw new UnsupportedOperationException();
            }
            @Override public TranslationProvider getObject(Object... args) {
                throw new UnsupportedOperationException();
            }
            @Override public @Nullable TranslationProvider getIfAvailable() {
                return provider;
            }
            @Override public @Nullable TranslationProvider getIfUnique() {
                return provider;
            }
        };
    }

    private static ArticleDocument article() {
        ArticleDocument article = new ArticleDocument();
        article.setId(ID);
        article.setTitle("Council approves transit plan");
        article.setSummary("The vote ended a three-year debate.");
        return article;
    }

    /** Records what it was asked to translate and echoes the language back. */
    private static class Recording implements TranslationProvider {
        final List<String> titles = new ArrayList<>();
        final List<String> summaries = new ArrayList<>();
        int calls;

        @Override public String name() { return "test"; }
        @Override public TranslatedText translate(String title, @Nullable String summary,
                String targetLanguage) {
            calls++;
            titles.add(title);
            summaries.add(String.valueOf(summary));
            return new TranslatedText("[" + targetLanguage + "] " + title,
                    summary == null ? null : "[" + targetLanguage + "] " + summary,
                    "test-model-1");
        }
    }

    @Test
    void a_translation_is_recorded_as_an_enrichment_not_on_the_article() {
        // The article is what was collected; a translation is what a model
        // made of it later, and re-running must not destroy the earlier one.
        TranslationService service = serviceWith(new Recording());

        assertThat(service.translate(article(), NOW)).isTrue();

        ArgumentCaptor<EnrichmentDocument> stored =
                ArgumentCaptor.forClass(EnrichmentDocument.class);
        verify(enrichmentService).record(stored.capture());
        EnrichmentDocument enrichment = stored.getValue();
        assertThat(enrichment.getArticleId()).isEqualTo(ID);
        assertThat(enrichment.getType()).isEqualTo(EnrichmentType.TRANSLATION);
        assertThat(enrichment.getLanguage()).isEqualTo("de");
        assertThat(enrichment.getProducer()).isEqualTo("test");
        assertThat(enrichment.getModel()).isEqualTo("test-model-1");
        assertThat(enrichment.getCreatedAt()).isEqualTo(NOW);
        assertThat(enrichment.getContent())
                .containsEntry("title", "[de] Council approves transit plan")
                .containsEntry("summary", "[de] The vote ended a three-year debate.");
        verify(articleService).recordTranslated(
                ID, "[de] Council approves transit plan", "[de] The vote ended a three-year debate.");
    }

    @Test
    void the_translation_is_mirrored_onto_the_article_so_it_can_be_searched() {
        // MongoDB allows one text index per collection, so searchable text
        // has to sit on the document being searched. The enrichment stays
        // the record; this is a derived copy with exactly one writer.
        TranslationService service = serviceWith(new Recording());

        assertThat(service.translate(article(), NOW)).isTrue();

        verify(articleService).recordTranslated(
                ID, "[de] Council approves transit plan",
                "[de] The vote ended a three-year debate.");
    }

    @Test
    void a_translation_without_a_teaser_mirrors_a_null_rather_than_a_blank() {
        ArticleDocument article = article();
        article.setSummary(null);
        TranslationService service = serviceWith(new Recording());

        assertThat(service.translate(article, NOW)).isTrue();

        // An empty string in the text index is noise, and it would stamp a
        // blank over whatever a previous run had put there.
        verify(articleService).recordTranslated(
                ID, "[de] Council approves transit plan", null);
    }

    @Test
    void an_unknown_model_is_recorded_as_unknown_rather_than_guessed() {
        // The provider may not learn which model answered — an older kit,
        // or a call that left no trace. The record then has to say so: a
        // plausible substitute would read like evidence of something that
        // was never observed, and comparing two runs is the whole reason
        // the model is stored at all.
        TranslationService service = serviceWith(new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public TranslatedText translate(String title,
                    @Nullable String summary, String targetLanguage) {
                return new TranslatedText("Titel", "Teaser", null);
            }
        });

        assertThat(service.translate(article(), NOW)).isTrue();

        ArgumentCaptor<EnrichmentDocument> stored =
                ArgumentCaptor.forClass(EnrichmentDocument.class);
        verify(enrichmentService).record(stored.capture());
        assertThat(stored.getValue().getModel()).isNull();
        assertThat(stored.getValue().getProducer())
                .as("the producer is still known even when the model is not")
                .isEqualTo("test");
    }

    @Test
    void title_and_teaser_go_in_one_call() {
        // The prompt dominates the token bill, so a second call would
        // double the expensive half to save nothing.
        Recording provider = new Recording();

        serviceWith(provider).translate(article(), NOW);

        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.titles).containsExactly("Council approves transit plan");
        assertThat(provider.summaries).containsExactly("The vote ended a three-year debate.");
    }

    @Test
    void the_teaser_can_be_left_out() {
        properties.getTranslation().setTranslateSummary(false);
        Recording provider = new Recording();

        serviceWith(provider).translate(article(), NOW);

        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.summaries).containsExactly("null");
        ArgumentCaptor<EnrichmentDocument> stored =
                ArgumentCaptor.forClass(EnrichmentDocument.class);
        verify(enrichmentService).record(stored.capture());
        assertThat(stored.getValue().getContent()).doesNotContainKey("summary");
    }

    @Test
    void an_article_without_a_teaser_is_still_translated() {
        ArticleDocument article = article();
        article.setSummary(null);

        assertThat(serviceWith(new Recording()).translate(article, NOW)).isTrue();
    }

    @Test
    void a_retryable_failure_keeps_the_attempt_count_so_the_backoff_applies() {
        ArticleDocument article = article();
        article.setTranslationAttempts(1);
        TranslationProvider failing = new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public TranslatedText translate(String t, @Nullable String s, String l) {
                throw TranslationException.transient_("brain unreachable", null);
            }
        };

        assertThat(serviceWith(failing).translate(article, NOW)).isFalse();

        verify(articleService).recordTranslationFailure(eq(ID), any(), eq(1), eq(NOW));
        verify(enrichmentService, never()).record(any());
    }

    @Test
    void a_permanent_failure_spends_the_whole_budget_at_once() {
        // Retrying a rejected token four more times produces four more
        // rejections. The queue should say so and move on.
        ArticleDocument article = article();
        article.setTranslationAttempts(1);
        TranslationProvider rejecting = new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public TranslatedText translate(String t, @Nullable String s, String l) {
                throw TranslationException.permanent("token rejected", null);
            }
        };

        serviceWith(rejecting).translate(article, NOW);

        verify(articleService).recordTranslationFailure(eq(ID), any(),
                eq(properties.getTranslation().getMaxAttempts()), eq(NOW));
    }

    @Test
    void an_article_without_a_title_is_dropped_rather_than_retried() {
        ArticleDocument article = article();
        article.setTitle("  ");

        assertThat(serviceWith(new Recording()).translate(article, NOW)).isFalse();

        verify(articleService).recordTranslationFailure(eq(ID), any(),
                eq(properties.getTranslation().getMaxAttempts()), eq(NOW));
    }

    @Test
    void without_a_pivot_language_nothing_happens() {
        properties.getTranslation().setPivotLanguage("");

        assertThat(serviceWith(new Recording()).translate(article(), NOW)).isFalse();
        verify(enrichmentService, never()).record(any());
        verify(articleService, never()).recordTranslationFailure(any(), any(), anyInt(), any());
    }

    @Test
    void without_a_provider_the_service_reports_unavailable_and_does_nothing() {
        // Munin still queues; nothing drains. A legible state, not a
        // broken one — the tick warns about it at startup.
        TranslationService service = serviceWith(null);

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.providerName()).isNull();
        assertThat(service.translate(article(), NOW)).isFalse();
        verify(enrichmentService, never()).record(any());
    }

    @Test
    void long_source_text_is_truncated_before_it_reaches_the_provider() {
        properties.getTranslation().setMaxSourceChars(50);
        ArticleDocument article = article();
        article.setTitle("x".repeat(500));
        Recording provider = new Recording();

        serviceWith(provider).translate(article, NOW);

        assertThat(provider.titles.getFirst().length()).isLessThanOrEqualTo(52);
    }
}
