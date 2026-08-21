package de.mhus.hrafnagud.jaglan;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleImage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rendering, which is the file somebody reads.
 *
 * <p>Two groups of tests: that a bodyless article is still a usable document,
 * and that publisher text cannot break the front matter. The second is the one
 * with teeth — every value in that block is publisher-controlled.
 */
class ArticleMarkdownTest {

    private static final Instant SEEN = Instant.parse("2026-08-21T14:37:02Z");

    private static ArticleDocument.ArticleDocumentBuilder article() {
        return ArticleDocument.builder()
                .id("68a7c1f2e4b09d3a5c6e7f80")
                .title("Rat beschliesst Plan")
                .url("https://example.com/story")
                .summary("Die Abstimmung beendet eine Debatte.")
                .language("de")
                .sourceNames(List.of("example-com-abc123"))
                .categories(List.of("Politik"))
                .publishedAt(Instant.parse("2026-08-21T09:00:00Z"))
                .firstSeenAt(SEEN);
    }

    // ─── Without a body ───

    @Test
    void withoutABody_theArticleIsStillAFile() {
        // The case that decides whether the mount looks broken while
        // munin.content.enabled is off.
        String out = ArticleMarkdown.render(article().build(), null);

        assertThat(out)
                .contains("title: \"Rat beschliesst Plan\"")
                .contains("# Rat beschliesst Plan")
                .contains("*Die Abstimmung beendet eine Debatte.*")
                .contains("[Original](https://example.com/story)");
    }

    @Test
    void missingBody_isSaidRatherThanLeftBlank() {
        // "Nothing here" and "the archive is broken" must not look the same.
        assertThat(ArticleMarkdown.render(article().build(), null))
                .contains("_No article body has been fetched for this article._");
    }

    @Test
    void frontMatter_carriesBothTimestamps() {
        // Collected and published disagree routinely, and the path is derived
        // from the first — so a reader has to be able to see both.
        assertThat(ArticleMarkdown.render(article().build(), null))
                .contains("published: \"2026-08-21T09:00:00Z\"")
                .contains("collected: \"2026-08-21T14:37:02Z\"");
    }

    @Test
    void everyDeliveringSource_isNamed() {
        // A wire report from six outlets is one article with six deliverers,
        // and naming only the first would misreport how widely it was carried.
        String out = ArticleMarkdown.render(
                article().sourceNames(List.of("faz-net-ab9ae4", "spiegel-de-112233")).build(), null);

        assertThat(out).contains("sources: [\"faz-net-ab9ae4\", \"spiegel-de-112233\"]");
    }

    @Test
    void absentFields_areOmittedNotEmpty() {
        String out = ArticleMarkdown.render(article()
                .summary(null).language(null).publishedAt(null)
                .categories(List.of()).build(), null);

        assertThat(out)
                .doesNotContain("language:")
                .doesNotContain("published:")
                .doesNotContain("categories:");
    }

    // ─── With a body ───

    @Test
    void withABody_theTextFollowsTheTeaser() {
        ArticleContentDocument content = ArticleContentDocument.builder()
                .text("Der Rat stimmte am Donnerstag zu.")
                .wordCount(827)
                .author("Julian Staib")
                .extractor("json-ld")
                .build();

        String out = ArticleMarkdown.render(article().build(), content);

        assertThat(out)
                .contains("author: \"Julian Staib\"")
                .contains("extractor: \"json-ld\"")
                .contains("words: 827")
                .contains("Der Rat stimmte am Donnerstag zu.")
                .doesNotContain("_No article body");
    }

    @Test
    void extractedTitle_winsOverTheFeedTitle() {
        // The page's own headline is the better one where extraction found it;
        // feed titles carry section prefixes and site names.
        ArticleContentDocument content = ArticleContentDocument.builder()
                .extractedTitle("Rat beschliesst Plan gegen Widerstand")
                .text("Text.")
                .build();

        assertThat(ArticleMarkdown.render(article().build(), content))
                .contains("# Rat beschliesst Plan gegen Widerstand");
    }

    @Test
    void leadImage_fallsBackToTheFeedsOwn() {
        // A feed entry often carries an image where the page hid it behind a
        // lazy-loading attribute.
        String out = ArticleMarkdown.render(
                article().imageUrl("https://example.com/feed.jpg").build(),
                ArticleContentDocument.builder().text("Text.").build());

        assertThat(out).contains("![](https://example.com/feed.jpg)");
    }

    @Test
    void leadImage_prefersWhatExtractionDeclared() {
        ArticleContentDocument content = ArticleContentDocument.builder()
                .imageUrl("https://example.com/og.jpg")
                .images(List.of(ArticleImage.builder()
                        .url("https://example.com/inline.jpg").role("INLINE").build()))
                .text("Text.")
                .build();

        assertThat(ArticleMarkdown.render(article().imageUrl("https://example.com/feed.jpg").build(),
                content))
                .contains("![](https://example.com/og.jpg)")
                .doesNotContain("inline.jpg");
    }

    @Test
    void imageLinks_stayAsThePublisherWroteThem() {
        // Rewriting to vance:/_ext/… needs to know whether a local copy
        // exists, per image. Until then the publisher URL is the honest value,
        // and it is also what the rewrite degrades to.
        assertThat(ArticleMarkdown.render(
                article().imageUrl("https://cdn.example.com/a.jpg").build(), null))
                .contains("https://cdn.example.com/a.jpg")
                .doesNotContain("_ext/");
    }

    // ─── Publisher text cannot break the file ───

    @Test
    void quotesAndColons_doNotBreakTheFrontMatter() {
        String out = ArticleMarkdown.render(article()
                .title("Er sagte: \"Das ist es\" — Reaktionen")
                .build(), null);

        assertThat(out).contains("title: \"Er sagte: \\\"Das ist es\\\" — Reaktionen\"");
    }

    @Test
    void newlinesInAValue_areFolded() {
        // A front-matter value is one line by construction; a raw newline
        // would end the scalar and turn the rest into a bogus key.
        String out = ArticleMarkdown.render(article().title("Zeile eins\nZeile zwei").build(), null);

        assertThat(out).contains("title: \"Zeile eins Zeile zwei\"");
        assertThat(out.lines().filter(line -> line.startsWith("title:")).count()).isEqualTo(1);
    }

    @Test
    void aLeadingDash_doesNotBecomeAList() {
        assertThat(ArticleMarkdown.render(article().title("- Aufmacher -").build(), null))
                .contains("title: \"- Aufmacher -\"");
    }

    @Test
    void backslashes_areEscaped() {
        assertThat(ArticleMarkdown.render(article().title("C:\\Pfad").build(), null))
                .contains("title: \"C:\\\\Pfad\"");
    }

    @Test
    void frontMatter_isClosedBeforeTheBody() {
        // The delimiters have to be exactly two, or the whole file is front
        // matter as far as a parser is concerned.
        String out = ArticleMarkdown.render(article().build(), null);
        List<String> delimiters = out.lines().filter("---"::equals).toList();

        assertThat(out).startsWith("---\n");
        // Two for the block, one for the rule above the source link.
        assertThat(delimiters).hasSize(3);
    }

    @Test
    void renderBytes_matchesTheStringLength() {
        // The listing reports this as the file size, so the two must agree.
        ArticleDocument article = article().build();

        assertThat(ArticleMarkdown.renderBytes(article, null))
                .hasSize(ArticleMarkdown.render(article, null)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
