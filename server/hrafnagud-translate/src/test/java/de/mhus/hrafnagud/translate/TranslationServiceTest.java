package de.mhus.hrafnagud.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.article.ArticleTranslation;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The queue's behaviour around a provider: what it stores, what it
 * retries, and what it refuses to let one failure take down with it.
 */
class TranslationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final String ID = "a1";

    private ArticleService articleService;
    private MuninProperties properties;

    @BeforeEach
    void setUp() {
        articleService = mock(ArticleService.class);
        properties = new MuninProperties();
    }

    private TranslationService serviceWith(@Nullable TranslationProvider provider) {
        return new TranslationService(articleService, properties, objectProviderOf(provider));
    }

    /** Minimal ObjectProvider: only getIfAvailable is exercised. */
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

    private static ArticleDocument article(String... pending) {
        ArticleDocument article = new ArticleDocument();
        article.setId(ID);
        article.setTitle("Council approves transit plan");
        article.setSummary("The vote ended a three-year debate.");
        article.setPendingTranslations(new ArrayList<>(List.of(pending)));
        return article;
    }

    /** Provider that echoes the target language into the result. */
    private static TranslationProvider echoing() {
        return new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String targetLanguage) {
                return "[" + targetLanguage + "] " + text;
            }
        };
    }

    @Test
    void translates_the_first_pending_language_and_stores_it() {
        TranslationService service = serviceWith(echoing());

        String done = service.translateNext(article("de", "fr"), NOW);

        assertThat(done).isEqualTo("de");
        ArgumentCaptor<ArticleTranslation> stored =
                ArgumentCaptor.forClass(ArticleTranslation.class);
        verify(articleService).recordTranslation(eq(ID), eq("de"), stored.capture(), eq(NOW));
        assertThat(stored.getValue().getTitle()).isEqualTo("[de] Council approves transit plan");
        assertThat(stored.getValue().getSummary())
                .isEqualTo("[de] The vote ended a three-year debate.");
        assertThat(stored.getValue().getEngine()).isEqualTo("test");
    }

    @Test
    void one_language_per_call_so_a_later_failure_cannot_cost_an_earlier_success() {
        // Two pending languages are two units of work. Draining both here
        // would put them under one retry and one lease.
        TranslationService service = serviceWith(echoing());

        service.translateNext(article("de", "fr"), NOW);

        verify(articleService).recordTranslation(eq(ID), eq("de"), any(), any());
        verify(articleService, never()).recordTranslation(eq(ID), eq("fr"), any(), any());
    }

    @Test
    void summary_translation_can_be_switched_off() {
        properties.getTranslation().setTranslateSummary(false);
        TranslationService service = serviceWith(echoing());

        service.translateNext(article("de"), NOW);

        ArgumentCaptor<ArticleTranslation> stored =
                ArgumentCaptor.forClass(ArticleTranslation.class);
        verify(articleService).recordTranslation(any(), any(), stored.capture(), any());
        assertThat(stored.getValue().getTitle()).isNotBlank();
        assertThat(stored.getValue().getSummary()).isNull();
    }

    @Test
    void a_failing_summary_does_not_cost_the_title() {
        // A translated headline with an untranslated teaser is a usable
        // archive entry; losing both to one bad call is not.
        TranslationProvider titleOnly = new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String targetLanguage) {
                if (text.startsWith("The vote")) {
                    throw TranslationException.transient_("summary blew up", null);
                }
                return "[" + targetLanguage + "] " + text;
            }
        };

        String done = serviceWith(titleOnly).translateNext(article("de"), NOW);

        assertThat(done).isEqualTo("de");
        ArgumentCaptor<ArticleTranslation> stored =
                ArgumentCaptor.forClass(ArticleTranslation.class);
        verify(articleService).recordTranslation(any(), any(), stored.capture(), any());
        assertThat(stored.getValue().getTitle()).isNotBlank();
        assertThat(stored.getValue().getSummary()).isNull();
    }

    @Test
    void a_retryable_failure_keeps_the_attempt_count_so_the_backoff_applies() {
        ArticleDocument article = article("de");
        article.setTranslationAttempts(1);
        TranslationProvider failing = new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String targetLanguage) {
                throw TranslationException.transient_("brain unreachable", null);
            }
        };

        String done = serviceWith(failing).translateNext(article, NOW);

        assertThat(done).isNull();
        verify(articleService).recordTranslationFailure(eq(ID), eq("de"), any(), eq(1), eq(NOW));
    }

    @Test
    void a_permanent_failure_spends_the_whole_budget_at_once() {
        // Retrying a rejected token four more times produces four more
        // rejections. The queue should say so and move on.
        ArticleDocument article = article("de");
        article.setTranslationAttempts(1);
        TranslationProvider rejecting = new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String targetLanguage) {
                throw TranslationException.permanent("token rejected", null);
            }
        };

        serviceWith(rejecting).translateNext(article, NOW);

        verify(articleService).recordTranslationFailure(eq(ID), eq("de"), any(),
                eq(properties.getTranslation().getMaxAttempts()), eq(NOW));
    }

    @Test
    void an_article_without_a_title_is_dropped_rather_than_retried() {
        ArticleDocument article = article("de");
        article.setTitle("  ");

        String done = serviceWith(echoing()).translateNext(article, NOW);

        assertThat(done).isNull();
        verify(articleService).recordTranslationFailure(eq(ID), eq("de"), any(),
                eq(properties.getTranslation().getMaxAttempts()), eq(NOW));
    }

    @Test
    void nothing_pending_is_not_an_error() {
        assertThat(serviceWith(echoing()).translateNext(article(), NOW)).isNull();
        verify(articleService, never()).recordTranslation(any(), any(), any(), any());
        verify(articleService, never())
                .recordTranslationFailure(any(), any(), any(), anyInt(), any());
    }

    @Test
    void without_a_provider_the_service_reports_unavailable_and_does_nothing() {
        // Munin still queues; nothing drains. A legible state, not a
        // broken one — the tick warns about it at startup.
        TranslationService service = serviceWith(null);

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.providerName()).isNull();
        assertThat(service.translateNext(article("de"), NOW)).isNull();
        verify(articleService, never()).recordTranslation(any(), any(), any(), any());
    }

    @Test
    void long_source_text_is_truncated_before_it_reaches_the_provider() {
        properties.getTranslation().setMaxSourceChars(50);
        ArticleDocument article = article("de");
        article.setTitle("x".repeat(500));
        List<Integer> seenLengths = new ArrayList<>();
        TranslationProvider measuring = new TranslationProvider() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String targetLanguage) {
                seenLengths.add(text.length());
                return "ok";
            }
        };

        serviceWith(measuring).translateNext(article, NOW);

        assertThat(seenLengths).isNotEmpty();
        assertThat(seenLengths.getFirst()).isLessThanOrEqualTo(52);
    }
}
