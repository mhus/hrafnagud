package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which languages an article is queued for at ingest. */
class ArticleFactoryTranslationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static ArticleDocument build(String articleLanguage, List<String> targets) {
        LanguageSource source = articleLanguage == null
                ? LanguageSource.UNKNOWN : LanguageSource.DETECTED;
        return ArticleFactory.build(
                ArticleCandidate.builder().url("https://example.com/a").originalUrl("x")
                        .title("Title").build(),
                SourceDocument.builder().name("src").build(),
                new LanguageResolver.Resolution(articleLanguage, source),
                ContentStatus.PENDING, targets, NOW);
    }

    @Test
    void every_target_is_queued() {
        assertThat(build("en", List.of("de", "fr")).getPendingTranslations())
                .containsExactly("de", "fr");
    }

    @Test
    void the_articles_own_language_is_not_queued() {
        // A model asked to translate German into German can only return
        // what it was given, at full price.
        assertThat(build("de", List.of("de", "en")).getPendingTranslations())
                .containsExactly("en");
    }

    @Test
    void an_unknown_source_language_keeps_every_target() {
        // Without knowing what it is, no target can be ruled out — and a
        // provider handed text already in the target language returns it
        // unchanged, so the cost of being wrong is one call, not a lost
        // language.
        assertThat(build(null, List.of("de", "en")).getPendingTranslations())
                .containsExactly("de", "en");
    }

    @Test
    void targets_are_normalised_and_deduplicated() {
        assertThat(build("en", List.of("de-DE", "DE", "de")).getPendingTranslations())
                .containsExactly("de");
    }

    @Test
    void unusable_targets_are_dropped() {
        assertThat(build("en", List.of("german", "", "de")).getPendingTranslations())
                .containsExactly("de");
    }

    @Test
    void no_targets_means_no_queue_marker() {
        // Only a queued article belongs in the partial index.
        ArticleDocument article = build("en", List.of());

        assertThat(article.getPendingTranslations()).isEmpty();
        assertThat(article.getTranslationNextAttemptAt()).isNull();
    }

    @Test
    void a_queued_article_is_due_immediately() {
        assertThat(build("en", List.of("de")).getTranslationNextAttemptAt()).isEqualTo(NOW);
    }
}
