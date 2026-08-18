package de.mhus.hrafnagud.munin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Extraction against the fixture corpus.
 *
 * <p>Extraction fails <em>silently</em> — a navigation rail stored as
 * article text throws nothing and is discovered months later. The corpus is
 * the only thing that turns that into a test failure, so each fixture
 * asserts both halves: the prose survived, and the chrome did not.
 *
 * <p>Fixtures are synthetic reproductions of real page structures, not
 * saved pages; see {@code src/test/resources/pages/README.md} for why.
 */
class ContentExtractorFixtureTest {

    private final ContentExtractor extractor = new ContentExtractor();

    private ExtractedArticle extract(String fixture, String baseUri) {
        return extractor.extract(ExtractionFixtures.load(fixture), baseUri);
    }

    // ─── json-ld rung ───

    @Test
    void german_jsonLdBody_winsOverTheDom() {
        ExtractedArticle result =
                extract("de-jsonld-full.html", "https://beispiel.example/politik/plan");

        assertThat(result.getExtractor()).isEqualTo("json-ld");
        // The DOM carries only the first two paragraphs; the publisher's own
        // body has all four.
        assertThat(result.getText()).contains("Planfeststellungsverfahren");
        assertThat(result.getWordCount()).isGreaterThan(100);
    }

    @Test
    void german_metadataComesFromTheDeclaration() {
        ExtractedArticle result =
                extract("de-jsonld-full.html", "https://beispiel.example/politik/plan");

        assertThat(result.getTitle()).isEqualTo("Stadtrat beschließt lange verzögerten Verkehrsplan");
        assertThat(result.getLanguage()).isEqualTo("de");
        assertThat(result.getAuthor()).isEqualTo("Anna Beispiel");
        assertThat(result.getPublishedAt()).isEqualTo(Instant.parse("2026-08-18T07:30:00Z"));
        assertThat(result.getCanonicalUrl())
                .isEqualTo("https://beispiel.example/politik/verkehrsplan-beschlossen");
    }

    @Test
    void german_chromeIsNotInTheText() {
        ExtractedArticle result =
                extract("de-jsonld-full.html", "https://beispiel.example/politik/plan");

        assertThat(result.getText()).doesNotContain("Impressum");
        assertThat(result.getText()).doesNotContain("Kommentar:");
    }

    @Test
    void japanese_graphWrappedJsonLd_isFound() {
        ExtractedArticle result = extract("ja-graph.html", "https://reishi.test/story");

        assertThat(result.getExtractor()).isEqualTo("json-ld");
        assertThat(result.getLanguage()).isEqualTo("ja");
        assertThat(result.getText()).contains("着工は来春の予定");
    }

    @Test
    void italian_typeArrayAndImageObjects_areRead() {
        ExtractedArticle result = extract("it-type-array.html", "https://esempio.test/story");

        assertThat(result.getExtractor()).isEqualTo("json-ld");
        assertThat(result.getLanguage()).isEqualTo("it");
        assertThat(result.leadImage()).isNotNull();
        assertThat(result.leadImage().getUrl()).isEqualTo("https://esempio.test/img/consiglio.jpg");
    }

    @Test
    void english_shortJsonLdBody_fallsThroughToTheDom() {
        // A one-sentence articleBody is a description in the wrong field.
        // Trusting it would replace a full article with a teaser.
        ExtractedArticle result =
                extract("en-short-jsonld-body.html", "https://example.test/story");

        assertThat(result.getExtractor()).isEqualTo("semantic");
        assertThat(result.getText()).contains("Construction is scheduled");
        // The declaration's metadata is still used.
        assertThat(result.getLanguage()).isEqualTo("en");
    }

    // ─── semantic rung ───

    @Test
    void english_semanticArticle_isChosen() {
        ExtractedArticle result = extract("en-semantic.html", "https://example.test/story");

        assertThat(result.getExtractor()).isEqualTo("semantic");
        assertThat(result.getText()).contains("ridership figures");
        assertThat(result.getText()).doesNotContain("morning briefing");
        assertThat(result.getText()).doesNotContain("Everything you need to know");
    }

    @Test
    void english_multipleAuthors_areJoined() {
        ExtractedArticle result = extract("en-semantic.html", "https://example.test/story");

        assertThat(result.getAuthor()).isEqualTo("Jordan Example, Sam Placeholder");
    }

    @Test
    void german_chromeClassesInGermanAreStripped() {
        // A worldwide collector cannot recognise chrome only in English.
        ExtractedArticle result = extract("de-chrome.html", "https://beispiel.example/story");

        assertThat(result.getText()).contains("Fahrgastzahlen benachbarter Städte");
        assertThat(result.getText()).doesNotContain("Cookies");
        assertThat(result.getText()).doesNotContain("Reiseportal");
        assertThat(result.getText()).doesNotContain("Anzeige:");
        assertThat(result.getText()).doesNotContain("Auf Facebook teilen");
        assertThat(result.getText()).doesNotContain("Ein Leser schreibt");
    }

    // ─── scored rung ───

    @Test
    void russian_withoutSemanticMarkup_fallsBackToScoring() {
        ExtractedArticle result = extract("ru-scored.html", "https://primer.test/story");

        assertThat(result.getExtractor()).isEqualTo("scored");
        assertThat(result.getText()).contains("окупится за двенадцать лет");
        // The link rail has more text but almost all of it is inside anchors.
        assertThat(result.getText()).doesNotContain("Бюджет города на следующий год");
    }

    @Test
    void portuguese_divSoupWithNoMetadata_stillYieldsTheProse() {
        ExtractedArticle result = extract("pt-bare.html", "https://exemplo.test/story");

        assertThat(result.getText()).contains("comparticipação estatal");
        assertThat(result.getText()).doesNotContain("Desporto");
        assertThat(result.getWordCount()).isGreaterThan(60);
    }

    // ─── paywall ───

    @Test
    void french_paywall_isDetectedAndTheTextIsShort() {
        ExtractedArticle result = extract("fr-paywall.html", "https://exemple.test/story");

        assertThat(result.isGated()).isTrue();
        // Short enough that the caller's word-count floor turns this into a
        // PAYWALL verdict rather than a stored article.
        assertThat(result.getWordCount()).isLessThan(60);
    }

    // ─── images ───

    @Test
    void german_lazyLoadedImage_resolvesToTheRealFileNotThePlaceholder() {
        ExtractedArticle result =
                extract("de-jsonld-full.html", "https://beispiel.example/politik/plan");

        List<ExtractedImage> inline = result.getImages().stream()
                .filter(image -> image.getRole() == ImageRole.INLINE)
                .toList();

        assertThat(inline).hasSize(1);
        // srcset wins and the widest candidate is taken; the base64
        // placeholder in src must not survive.
        assertThat(inline.getFirst().getUrl())
                .isEqualTo("https://beispiel.example/bilder/ratssaal-1200.jpg");
        assertThat(result.getImages()).noneMatch(image -> image.getUrl().startsWith("data:"));
    }

    @Test
    void german_figcaptionBecomesTheCaptionAndNotBodyText() {
        ExtractedArticle result =
                extract("de-jsonld-full.html", "https://beispiel.example/politik/plan");

        assertThat(result.getImages()).anyMatch(image ->
                "Die Abstimmung im Ratssaal dauerte bis kurz vor Mitternacht."
                        .equals(image.getCaption()));
        assertThat(result.getText()).doesNotContain("dauerte bis kurz vor Mitternacht");
    }

    @Test
    void german_leadImageComesFromTheDeclaration() {
        ExtractedArticle result =
                extract("de-jsonld-full.html", "https://beispiel.example/politik/plan");

        assertThat(result.leadImage()).isNotNull();
        assertThat(result.leadImage().getUrl())
                .isEqualTo("https://beispiel.example/bilder/ratssaal-gross.jpg");
        assertThat(result.getImages().getFirst().getRole()).isEqualTo(ImageRole.LEAD);
    }

    @Test
    void spanish_dataSrcAndDataOriginal_areBothResolved() {
        ExtractedArticle result = extract("es-lazy-images.html", "https://ejemplo.test/story");

        assertThat(result.getImages()).extracting(ExtractedImage::getUrl)
                .contains("https://ejemplo.test/imagenes/pleno-grande.jpg",
                        "https://ejemplo.test/imagenes/anden.jpg");
    }

    @Test
    void spanish_trackingPixel_isDropped() {
        ExtractedArticle result = extract("es-lazy-images.html", "https://ejemplo.test/story");

        assertThat(result.getImages()).noneMatch(image -> image.getUrl().contains("1x1.gif"));
    }

    @Test
    void english_inlineImageCaptionIsKept() {
        ExtractedArticle result = extract("en-semantic.html", "https://example.test/story");

        assertThat(result.getImages()).anyMatch(image ->
                image.getRole() == ImageRole.INLINE
                        && "The northern line would add four stations by the end of the decade."
                                .equals(image.getCaption()));
    }

    @Test
    void imagesAreDeduplicated_whenTheLeadAlsoAppearsInline() {
        ExtractedArticle result = extract("en-semantic.html", "https://example.test/story");

        assertThat(result.getImages()).extracting(ExtractedImage::getUrl).doesNotHaveDuplicates();
    }

    @Test
    void noPageStoresImageBytes_onlyUrls() {
        // A structural guarantee worth asserting: every image reference is
        // an absolute http(s) URL, never inline data.
        for (String fixture : List.of("de-jsonld-full.html", "en-semantic.html",
                "es-lazy-images.html", "it-type-array.html")) {
            ExtractedArticle result = extract(fixture, "https://example.test/story");
            assertThat(result.getImages()).allSatisfy(image ->
                    assertThat(image.getUrl()).startsWith("http"));
        }
    }
}
