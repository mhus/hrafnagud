package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterOutcome;
import de.mhus.hrafnagud.api.filter.FilterOutcomes;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Whether an article is queued for translation at ingest. */
class ArticleFactoryTranslationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static ArticleDocument build(String articleLanguage, String pivot) {
        return build(articleLanguage, pivot, List.of());
    }

    private static ArticleDocument build(String articleLanguage, String pivot,
            List<String> readable) {
        LanguageSource source = articleLanguage == null
                ? LanguageSource.UNKNOWN : LanguageSource.DETECTED;
        return ArticleFactory.build(
                ArticleCandidate.builder().url("https://example.com/a").originalUrl("x")
                        .title("Title").build(),
                SourceDocument.builder().name("src").build(),
                new LanguageResolver.Resolution(articleLanguage, source),
                ContentStatus.PENDING, TranslationLanguages.of(pivot, readable), NOW);
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
    void an_article_in_a_readable_language_is_skipped() {
        // The point of the list: a reader comfortable in English gains
        // nothing from an English article translated into German.
        ArticleDocument article = build("en", "de", List.of("en"));

        assertThat(article.getTranslationStatus()).isEqualTo(TranslationStatus.SKIPPED);
        assertThat(article.getTranslationNextAttemptAt()).isNull();
    }

    @Test
    void a_language_outside_the_list_is_still_queued() {
        assertThat(build("fr", "de", List.of("en")).getTranslationStatus())
                .isEqualTo(TranslationStatus.PENDING);
    }

    @Test
    void readable_languages_are_normalised_like_everything_else() {
        // `EN` in the setting and `en` on the article are the same language;
        // a set that compared them literally would silently never match.
        assertThat(build("en", "de", List.of("EN", "fr-FR")).getTranslationStatus())
                .isEqualTo(TranslationStatus.SKIPPED);
        assertThat(build("fr", "de", List.of("EN", "fr-FR")).getTranslationStatus())
                .isEqualTo(TranslationStatus.SKIPPED);
    }

    @Test
    void an_unknown_language_is_queued_even_with_a_readable_list() {
        // The list says which languages need no work, not that an article
        // nobody could classify needs none.
        assertThat(build(null, "de", List.of("en")).getTranslationStatus())
                .isEqualTo(TranslationStatus.PENDING);
    }

    @Test
    void a_readable_list_without_a_pivot_language_still_translates_nothing() {
        assertThat(build("fr", "", List.of("en")).getTranslationStatus())
                .isEqualTo(TranslationStatus.SKIPPED);
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

    // ─── Filter decisions ───

    private static ArticleDocument buildFiltered(String articleLanguage, String pivot,
            FilterOutcomes filters) {
        return ArticleFactory.build(
                ArticleCandidate.builder().url("https://example.com/a").originalUrl("x")
                        .title("Title").build(),
                SourceDocument.builder().name("src").build(),
                new LanguageResolver.Resolution(articleLanguage, LanguageSource.DETECTED),
                ContentStatus.PENDING, TranslationLanguages.of(pivot, List.of()),
                List.of(), List.of(), filters, NOW);
    }

    private static FilterOutcomes denyTranslation() {
        return new FilterOutcomes(FilterOutcome.defaultAccept(),
                FilterOutcome.of(FilterDecision.DENY, "deny-host-youtube-com"));
    }

    @Test
    void a_denied_article_is_not_queued_whatever_its_language() {
        ArticleDocument article = buildFiltered("en", "de", denyTranslation());

        assertThat(article.getTranslationStatus()).isEqualTo(TranslationStatus.SKIPPED);
        assertThat(article.getTranslationNextAttemptAt()).isNull();
    }

    /**
     * The distinction the policy fields exist for: two articles, both
     * {@code SKIPPED}, and only one of them undone by changing a rule.
     */
    @Test
    void the_reason_for_skipping_is_recorded_and_the_two_reasons_differ() {
        ArticleDocument filtered = buildFiltered("en", "de", denyTranslation());
        ArticleDocument sameLanguage = buildFiltered("de", "de", FilterOutcomes.unfiltered());

        assertThat(filtered.getTranslationPolicy()).isEqualTo(FilterDecision.DENY);
        assertThat(filtered.getTranslationPolicyRule()).isEqualTo("deny-host-youtube-com");

        assertThat(sameLanguage.getTranslationStatus()).isEqualTo(TranslationStatus.SKIPPED);
        assertThat(sameLanguage.getTranslationPolicy()).isEqualTo(FilterDecision.ACCEPT);
        assertThat(sameLanguage.getTranslationPolicyRule()).isNull();
    }

    @Test
    void an_unfiltered_build_records_an_accepted_policy_and_the_time() {
        ArticleDocument article = buildFiltered("en", "de", FilterOutcomes.unfiltered());

        assertThat(article.getContentPolicy()).isEqualTo(FilterDecision.ACCEPT);
        assertThat(article.getTranslationPolicy()).isEqualTo(FilterDecision.ACCEPT);
        assertThat(article.getPolicyAt()).isEqualTo(NOW);
    }

    /**
     * A translation rule must not take the article out of the content queue.
     * The two pipelines are separately expensive and separately decided.
     */
    @Test
    void denying_translation_leaves_the_body_decision_alone() {
        assertThat(buildFiltered("en", "de", denyTranslation()).getContentPolicy())
                .isEqualTo(FilterDecision.ACCEPT);
    }
}
