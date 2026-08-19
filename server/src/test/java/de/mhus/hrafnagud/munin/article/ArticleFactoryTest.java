package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArticleFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    private static SourceDocument source() {
        return SourceDocument.builder()
                .name("example-abc123")
                .categories(List.of("Germany"))
                .build();
    }

    private static ArticleCandidate candidate() {
        return ArticleCandidate.builder()
                .url("https://example.com/story")
                .originalUrl("https://example.com/story?utm_source=x")
                .title("Council approves plan")
                .summary("The vote ended a three-year debate.")
                .categories(List.of("Politics"))
                .build();
    }

    @Test
    void dedupKey_isStableForTheSameUrl() {
        assertThat(ArticleFactory.dedupKey("https://example.com/a"))
                .isEqualTo(ArticleFactory.dedupKey("https://example.com/a"));
    }

    @Test
    void dedupKey_differsPerUrl() {
        assertThat(ArticleFactory.dedupKey("https://example.com/a"))
                .isNotEqualTo(ArticleFactory.dedupKey("https://example.com/b"));
    }

    @Test
    void contentHash_ignoresCaseAndWhitespaceDifferences() {
        // Trivial editorial differences between outlets carrying the same
        // wire report should not produce different fingerprints.
        assertThat(ArticleFactory.contentHash("Council  Approves Plan", "The vote  ended."))
                .isEqualTo(ArticleFactory.contentHash("council approves plan", "the vote ended."));
    }

    @Test
    void contentHash_differsForDifferentStories() {
        assertThat(ArticleFactory.contentHash("Story A", "x"))
                .isNotEqualTo(ArticleFactory.contentHash("Story B", "x"));
    }

    @Test
    void build_setsIdentityAndProvenance() {
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution("de", LanguageSource.FEED),
                ContentStatus.PENDING, NOW);

        assertThat(article.getDedupKey())
                .isEqualTo(ArticleFactory.dedupKey("https://example.com/story"));
        assertThat(article.getFirstSourceName()).isEqualTo("example-abc123");
        assertThat(article.getSourceNames()).containsExactly("example-abc123");
        assertThat(article.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(article.getLastSourceAddedAt()).isEqualTo(NOW);
    }

    @Test
    void build_keepsTheOriginalUrlAlongsideTheNormalisedOne() {
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution(null, LanguageSource.UNKNOWN),
                ContentStatus.PENDING, NOW);

        assertThat(article.getUrl()).isEqualTo("https://example.com/story");
        assertThat(article.getOriginalUrl()).isEqualTo("https://example.com/story?utm_source=x");
    }

    @Test
    void build_carriesTheResolvedLanguageAndItsProvenance() {
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution("de", LanguageSource.DETECTED),
                ContentStatus.PENDING, NOW);

        assertThat(article.getLanguage()).isEqualTo("de");
        assertThat(article.getLanguageSource()).isEqualTo(LanguageSource.DETECTED);
    }

    @Test
    void build_derivesTheTextIndexStemmerFromTheLanguage() {
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution("de", LanguageSource.FEED),
                ContentStatus.PENDING, NOW);

        assertThat(article.getTextLanguage()).isEqualTo("german");
    }

    @Test
    void build_aLanguageMongodbCannotStemStillProducesAStorableArticle() {
        // The whole point: `ja` in the text-index override field is rejected
        // on write, and one such article aborted the entire poll of its feed.
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution("ja", LanguageSource.FEED),
                ContentStatus.PENDING, NOW);

        assertThat(article.getLanguage())
                .as("the article's own language stays the honest record")
                .isEqualTo("ja");
        assertThat(article.getTextLanguage()).isEqualTo(TextIndexLanguage.NONE);
    }

    @Test
    void build_anUnknownLanguageYieldsAStorableOverrideToo() {
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution(null, LanguageSource.UNKNOWN),
                ContentStatus.PENDING, NOW);

        assertThat(article.getTextLanguage()).isEqualTo(TextIndexLanguage.NONE);
    }

    @Test
    void build_pendingArticle_entersTheContentQueue() {
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution(null, LanguageSource.UNKNOWN),
                ContentStatus.PENDING, NOW);

        assertThat(article.getContentNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void build_skippedArticle_staysOutOfTheContentQueue() {
        // A queue timestamp on a terminal article would keep it in the
        // partial index for a worker that will never claim it.
        ArticleDocument article = ArticleFactory.build(candidate(), source(),
                new LanguageResolver.Resolution(null, LanguageSource.UNKNOWN),
                ContentStatus.SKIPPED, NOW);

        assertThat(article.getContentNextAttemptAt()).isNull();
    }

    @Test
    void mergeCategories_putsSourceCategoriesBeforeTheEntrysOwn() {
        List<String> merged =
                ArticleFactory.mergeCategories(List.of("Germany"), List.of("Politics"));

        assertThat(merged).containsExactly("Germany", "Politics");
    }

    @Test
    void mergeCategories_deduplicatesAndDropsBlanks() {
        List<String> merged =
                ArticleFactory.mergeCategories(List.of("News", " "), List.of("News", "Sport"));

        assertThat(merged).containsExactly("News", "Sport");
    }
}
