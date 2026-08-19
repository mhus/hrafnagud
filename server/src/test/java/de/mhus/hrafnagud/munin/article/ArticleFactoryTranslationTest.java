package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Whether an article is queued for translation at ingest. */
class ArticleFactoryTranslationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static ArticleDocument build(String articleLanguage, String pivot) {
        LanguageSource source = articleLanguage == null
                ? LanguageSource.UNKNOWN : LanguageSource.DETECTED;
        return ArticleFactory.build(
                ArticleCandidate.builder().url("https://example.com/a").originalUrl("x")
                        .title("Title").build(),
                SourceDocument.builder().name("src").build(),
                new LanguageResolver.Resolution(articleLanguage, source),
                ContentStatus.PENDING, pivot, NOW);
    }

    @Test
    void a_foreign_language_article_is_queued() {
        ArticleDocument article = build("en", "de");

        assertThat(article.getTranslationStatus()).isEqualTo(TranslationStatus.PENDING);
        assertThat(article.getTranslationNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void an_article_already_in_the_pivot_language_is_skipped() {
        // A model asked to translate German into German can only return
        // what it was given, at full price.
        ArticleDocument article = build("de", "de");

        assertThat(article.getTranslationStatus()).isEqualTo(TranslationStatus.SKIPPED);
        assertThat(article.getTranslationNextAttemptAt()).isNull();
    }

    @Test
    void an_unknown_source_language_is_queued_rather_than_guessed_at() {
        // Skipping wrongly loses the translation silently; queueing
        // wrongly costs one call that returns the text unchanged.
        assertThat(build(null, "de").getTranslationStatus())
                .isEqualTo(TranslationStatus.PENDING);
    }

    @Test
    void no_pivot_language_means_translation_is_off() {
        ArticleDocument article = build("en", "");

        assertThat(article.getTranslationStatus()).isEqualTo(TranslationStatus.SKIPPED);
        assertThat(article.getTranslationNextAttemptAt()).isNull();
    }

    @Test
    void the_pivot_language_is_normalised_before_comparison() {
        // `de-DE` and `de` are the same target; treating them as different
        // would queue every German article against itself.
        assertThat(build("de", "de-DE").getTranslationStatus())
                .isEqualTo(TranslationStatus.SKIPPED);
    }

    @Test
    void an_unusable_pivot_language_disables_translation() {
        assertThat(build("en", "german").getTranslationStatus())
                .isEqualTo(TranslationStatus.SKIPPED);
    }
}
