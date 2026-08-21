package de.mhus.hrafnagud.hugin.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.settings.TestSettings;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What happens when the provider says "not now".
 *
 * <p>On a free tier a rate limit is not an accident, it is the normal state.
 * With three attempts and a doubling delay, treating it as a failure would mark
 * the whole backlog {@code FAILED} in three rounds without a single translation
 * having been attempted — so a throttle charges the article nothing and stops
 * the worker instead.
 */
class TranslationThrottleTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final String ID = "article-1";

    private ArticleService articleService;
    private HuginProperties properties;

    @BeforeEach
    void setUp() {
        articleService = mock(ArticleService.class);
        properties = new HuginProperties();
        properties.getTranslation().setPivotLanguage("de");
    }

    /** A provider that always says it is rate-limited. */
    private static final class Throttling implements TranslationProvider {
        private int calls;

        @Override
        public String name() {
            return "throttling";
        }

        @Override
        public TranslatedText translate(String title, @Nullable String summary, String target) {
            calls++;
            throw TranslationException.throttled("429 quota exceeded", null, null);
        }
    }

    private TranslationService serviceWith(TranslationProvider provider,
            Map<String, String> overrides) {
        return new TranslationService(articleService, mock(EnrichmentService.class),
                TestSettings.build(new MuninProperties(), properties, overrides),
                TranslationServiceTest.objectProviderOf(provider));
    }

    private static ArticleDocument article() {
        ArticleDocument article = new ArticleDocument();
        article.setId(ID);
        article.setTitle("Council approves plan");
        article.setLanguage("en");
        return article;
    }

    @Test
    void a_throttled_article_is_deferred_and_not_charged_an_attempt() {
        TranslationService service = serviceWith(new Throttling(), Map.of());

        assertThat(service.translate(article(), NOW)).isFalse();

        // Back in the queue after the cooldown, with the attempt returned.
        verify(articleService).deferTranslation(ID,
                NOW.plus(new HuginProperties().getTranslation().getThrottleCooldown()));
        // And emphatically not recorded as a failure, which is what would
        // eventually mark it FAILED.
        verify(articleService, never())
                .recordTranslationFailure(any(), any(), anyInt(), any());
    }

    @Test
    void the_cooldown_length_is_a_setting() {
        TranslationService service = serviceWith(new Throttling(),
                Map.of("hugin.translation.throttleCooldown", "PT5M"));

        service.translate(article(), NOW);

        verify(articleService).deferTranslation(eq(ID), eq(NOW.plus(Duration.ofMinutes(5))));
    }

    /**
     * The worker waits, not the article: a provider that refused this call will
     * refuse the next nine, and claiming nine more to find out costs nine
     * leases.
     */
    @Test
    void the_whole_worker_waits_and_resumes_when_the_cooldown_expires() {
        TranslationService service = serviceWith(new Throttling(), Map.of());
        assertThat(service.throttled(NOW)).isFalse();

        service.translate(article(), NOW);

        assertThat(service.throttled(NOW)).isTrue();
        assertThat(service.throttled(NOW.plusSeconds(30))).isTrue();
        assertThat(service.throttled(NOW.plusSeconds(61))).isFalse();
        // Cleared rather than sticky: the next round is not told to wait again.
        assertThat(service.throttled(NOW.plusSeconds(62))).isFalse();
    }

    @Test
    void a_provider_that_names_a_wait_is_believed_over_the_setting() {
        TranslationProvider asks = new TranslationProvider() {
            @Override
            public String name() {
                return "asks";
            }

            @Override
            public TranslatedText translate(String t, @Nullable String s, String target) {
                throw TranslationException.throttled("429", Duration.ofMinutes(15), null);
            }
        };

        TranslationService service = serviceWith(asks, Map.of());
        service.translate(article(), NOW);

        verify(articleService).deferTranslation(ID, NOW.plus(Duration.ofMinutes(15)));
        assertThat(service.throttled(NOW.plus(Duration.ofMinutes(14)))).isTrue();
    }

    /** An ordinary failure still counts, or nothing would ever be given up on. */
    @Test
    void an_ordinary_failure_is_still_charged_and_does_not_stop_the_worker() {
        TranslationProvider failing = new TranslationProvider() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public TranslatedText translate(String t, @Nullable String s, String target) {
                throw TranslationException.transient_("connection reset", null);
            }
        };

        TranslationService service = serviceWith(failing, Map.of());
        assertThat(service.translate(article(), NOW)).isFalse();

        verify(articleService).recordTranslationFailure(eq(ID), any(), anyInt(), eq(NOW));
        verify(articleService, never()).deferTranslation(any(), any());
        assertThat(service.throttled(NOW)).isFalse();
    }
}
