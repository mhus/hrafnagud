package de.mhus.hrafnagud.munin.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Extraction is judged on one question: did the prose survive and did the
 * chrome not. Both halves matter — a navigation rail stored as article text
 * poisons every downstream consumer just as thoroughly as a missing body.
 */
class ContentExtractorTest {

    private final ContentExtractor extractor = new ContentExtractor();

    private static final String PROSE_1 =
            "The city council voted on Tuesday to approve the long-delayed transit plan, "
                    + "ending a debate that had run for the better part of three years.";
    private static final String PROSE_2 =
            "Opponents argued the cost estimates were optimistic, while supporters pointed "
                    + "to ridership figures from comparable projects in neighbouring regions.";

    @Test
    void extract_prefersTheSemanticArticleElement() {
        String html = """
                <html lang="en"><body>
                  <nav><a href="/a">Home</a><a href="/b">Politics</a><a href="/c">Sport</a></nav>
                  <article>
                    <p>%s</p>
                    <p>%s</p>
                  </article>
                  <footer><a href="/imprint">Imprint</a></footer>
                </body></html>
                """.formatted(PROSE_1, PROSE_2);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getText()).contains("transit plan").contains("ridership figures");
        assertThat(result.getText()).doesNotContain("Imprint").doesNotContain("Politics");
        assertThat(result.getExtractor()).isEqualTo("semantic");
    }

    @Test
    void extract_paragraphsAreSeparatedByBlankLines() {
        String html = "<article><p>%s</p><p>%s</p></article>".formatted(PROSE_1, PROSE_2);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getText()).isEqualTo(PROSE_1 + "\n\n" + PROSE_2);
    }

    @Test
    void extract_withoutSemanticMarkup_scoresContainersAndBeatsLinkHeavyOnes() {
        // The related-stories rail has more text than the article, but nearly
        // all of it sits inside anchors. Link density is what separates them.
        String html = """
                <html><body>
                  <div class="rail">
                    <a href="/1">A very long headline about something else entirely</a>
                    <a href="/2">Another long headline that pads out this container</a>
                    <a href="/3">And a third one, longer still, to outweigh the article</a>
                    <p><a href="/4">A paragraph that is entirely one enormous link element</a></p>
                  </div>
                  <div class="body-text">
                    <p>%s</p>
                    <p>%s</p>
                  </div>
                </body></html>
                """.formatted(PROSE_1, PROSE_2);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getText()).contains("transit plan");
        assertThat(result.getText()).doesNotContain("headline about something else");
        assertThat(result.getExtractor()).isEqualTo("scored");
    }

    @Test
    void extract_dropsContainersMarkedAsChrome() {
        String html = """
                <article>
                  <p>%s</p>
                  <div class="newsletter-signup"><p>Subscribe to our morning briefing today
                     and never miss another important story from our newsroom.</p></div>
                  <div class="related-stories"><p>More coverage of this developing story
                     can be found in our dedicated section for regional politics.</p></div>
                </article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getText()).contains("transit plan");
        assertThat(result.getText()).doesNotContain("morning briefing");
        assertThat(result.getText()).doesNotContain("developing story");
    }

    @Test
    void extract_removesScriptAndStylePayloads() {
        String html = """
                <article>
                  <script>window.dataLayer = [{'page':'article'}];</script>
                  <style>.hidden { display: none; }</style>
                  <p>%s</p>
                </article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getText()).doesNotContain("dataLayer").doesNotContain("display: none");
    }

    @Test
    void extract_skipsShortParagraphsThatAreCaptionsNotProse() {
        String html = "<article><p>Photo: Reuters</p><p>%s</p></article>".formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        // The caption is below the paragraph threshold for *scoring*, but it
        // is still part of the chosen container's text — what matters is that
        // it did not cause the container to be rejected.
        assertThat(result.getText()).contains("transit plan");
    }

    @Test
    void extract_readsOpenGraphTitleAndImage() {
        String html = """
                <html><head>
                  <meta property="og:title" content="Council approves transit plan"/>
                  <meta property="og:image" content="/img/lead.jpg"/>
                  <title>Council approves transit plan | Example News</title>
                </head><body><article><p>%s</p></article></body></html>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/news/story");

        assertThat(result.getTitle()).isEqualTo("Council approves transit plan");
        // Relative image paths are resolved against the page URL, or the
        // stored value would be unusable.
        assertThat(result.leadImage()).isNotNull();
        assertThat(result.leadImage().getUrl()).isEqualTo("https://example.com/img/lead.jpg");
        assertThat(result.leadImage().getRole()).isEqualTo(ImageRole.LEAD);
    }

    @Test
    void extract_imageWithoutAFigure_fallsBackToAltAsCaption() {
        String html = """
                <article><p>%s</p>
                <img src="https://example.com/img/a.jpg" width="800" height="600"
                     alt="A tram at the northern terminus"/></article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getImages()).singleElement().satisfies(image -> {
            assertThat(image.getCaption()).isEqualTo("A tram at the northern terminus");
            assertThat(image.getRole()).isEqualTo(ImageRole.INLINE);
            assertThat(image.getWidth()).isEqualTo(800);
        });
    }

    @Test
    void extract_promoImageWithNoCaptionNoAltAndNoSize_isDropped() {
        // A newspaper's own front-page thumbnail advertising a subscription
        // sits inside the article container but carries none of the signals
        // that mark an image as meant to be looked at.
        String html = """
                <article><p>%s</p>
                <a href="/abo"><img src="https://example.com/covers/latest.jpg"/></a>
                </article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getImages()).isEmpty();
    }

    @Test
    void extract_figureWithoutCaptionOrSize_isStillKept() {
        // Being wrapped in a figure is itself a statement that the image is
        // part of the content.
        String html = """
                <article><p>%s</p>
                <figure><img src="https://example.com/img/photo.jpg"/></figure></article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getImages()).hasSize(1);
    }

    @Test
    void extract_captionCreditIsNotGluedToTheDescription() {
        String html = """
                <article><p>%s</p>
                <figure>
                  <img src="https://example.com/img/a.jpg" alt="x"/>
                  <figcaption>An aerial view of the construction site.<span
                    class="credit">picture alliance/dpa</span></figcaption>
                </figure></article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getImages()).singleElement().satisfies(image ->
                assertThat(image.getCaption())
                        .isEqualTo("An aerial view of the construction site. picture alliance/dpa"));
    }

    @Test
    void extract_iconSizedImages_areDropped() {
        String html = """
                <article><p>%s</p>
                <img src="https://example.com/img/icon.png" width="16" height="16" alt="icon"/>
                </article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getImages()).isEmpty();
    }

    @Test
    void extract_imagesOutsideTheArticleContainer_areNotCollected() {
        // Position is the discriminator: inside the container that survived
        // stripping and won the scoring means part of the reporting.
        String html = """
                <html><body>
                  <article><p>%s</p>
                    <img src="https://example.com/img/inside.jpg" alt="inside"/></article>
                  <div class="promo"><img src="https://example.com/img/outside.jpg" alt="ad"/></div>
                </body></html>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getImages()).extracting(ExtractedImage::getUrl)
                .containsExactly("https://example.com/img/inside.jpg");
    }

    @Test
    void extract_readsTheCanonicalUrlWithoutActingOnIt() {
        String html = """
                <html><head><link rel="canonical" href="/news/canonical-path"/></head>
                <body><article><p>%s</p></article></body></html>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/news/tracked");

        assertThat(result.getCanonicalUrl()).isEqualTo("https://example.com/news/canonical-path");
    }

    @Test
    void extract_readsTheDeclaredLanguage() {
        String html = "<html lang=\"de-DE\"><body><article><p>%s</p></article></body></html>"
                .formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getLanguage()).isEqualTo("de");
    }

    @Test
    void extract_detectsAPaywallMarkerInTheMarkup() {
        String html = """
                <html><body>
                  <article><p>%s</p></article>
                  <div class="paywall-overlay">Please subscribe</div>
                </body></html>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.isGated()).isTrue();
    }

    @Test
    void extract_detectsAPaywallPhrase() {
        String html = """
                <article><p>Teaser only.</p>
                <p>This article is for subscribers. Already a subscriber? Sign in.</p></article>
                """;

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.isGated()).isTrue();
    }

    @Test
    void extract_ordinaryNewsletterBox_isNotMistakenForAPaywall() {
        // "Subscribe" alone appears on nearly every news page ever
        // published; only full phrases count.
        String html = """
                <html><body>
                  <article><p>%s</p></article>
                  <div class="promo">Subscribe to our newsletter for daily updates.</div>
                </body></html>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.isGated()).isFalse();
    }

    @Test
    void extract_wordCountReflectsTheExtractedProse() {
        String html = "<article><p>%s</p></article>".formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getWordCount()).isEqualTo(PROSE_1.split("\\s+").length);
    }

    @Test
    void extract_ofAPageWithNoProse_yieldsALowWordCountRatherThanFailing() {
        // The caller's word-count floor turns this into a retry or a
        // paywall verdict; the extractor's job is to report honestly.
        ExtractedArticle result = extractor.extract(
                "<html><body><nav><a href='/'>Home</a></nav></body></html>",
                "https://example.com/story");

        assertThat(result.getWordCount()).isLessThan(10);
    }

    @Test
    void extract_doesNotEmitNestedBlocksTwice() {
        String html = """
                <article>
                  <blockquote><p>%s</p></blockquote>
                </article>
                """.formatted(PROSE_1);

        ExtractedArticle result = extractor.extract(html, "https://example.com/story");

        assertThat(result.getText().split("transit plan", -1)).hasSize(2);
    }
}
