package de.mhus.hrafnagud.munin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * JSON-LD in the wild is template-generated and inconsistent in a handful
 * of specific ways — {@code @type} as an array, {@code image} as an object,
 * an {@code @graph} wrapper, {@code author} as a list. Each of those is a
 * shape a strict reader would drop, and dropping it means falling back to
 * guessing on a page that told us the answer.
 */
class JsonLdReaderTest {

    private static JsonLdArticle read(String html) {
        return JsonLdReader.read(Jsoup.parse(html, "https://example.test/story"));
    }

    @Test
    void read_pageWithoutJsonLd_isAbsent() {
        assertThat(read("<html><body><p>Nothing here</p></body></html>").isPresent()).isFalse();
    }

    @Test
    void read_newsArticle_extractsTheDeclaredFields() {
        JsonLdArticle article = read("""
                <html><head><script type="application/ld+json">
                {"@context":"https://schema.org","@type":"NewsArticle",
                 "headline":"Council approves plan","inLanguage":"en-GB",
                 "datePublished":"2026-08-18T07:30:00Z",
                 "author":{"@type":"Person","name":"Jordan Example"},
                 "articleSection":"Politics",
                 "image":"https://example.test/img/a.jpg",
                 "articleBody":"First paragraph.\\n\\nSecond paragraph."}
                </script></head><body></body></html>
                """);

        assertThat(article.isPresent()).isTrue();
        assertThat(article.getHeadline()).isEqualTo("Council approves plan");
        assertThat(article.getLanguage()).isEqualTo("en-GB");
        assertThat(article.getDatePublished()).isEqualTo(Instant.parse("2026-08-18T07:30:00Z"));
        assertThat(article.getAuthor()).isEqualTo("Jordan Example");
        assertThat(article.getSections()).containsExactly("Politics");
        assertThat(article.getImages()).containsExactly("https://example.test/img/a.jpg");
        assertThat(article.getArticleBody()).isEqualTo("First paragraph.\n\nSecond paragraph.");
    }

    @Test
    void read_typeAsArray_isRecognised() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":["Article","NewsArticle"],"headline":"H"}
                </script>
                """);

        assertThat(article.isPresent()).isTrue();
        assertThat(article.getHeadline()).isEqualTo("H");
    }

    @Test
    void read_graphWrapper_isTraversed() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[
                  {"@type":"WebSite","name":"Example"},
                  {"@type":"NewsArticle","headline":"Buried in a graph"}]}
                </script>
                """);

        assertThat(article.getHeadline()).isEqualTo("Buried in a graph");
    }

    @Test
    void read_topLevelArray_isTraversed() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                [{"@type":"Organization","name":"Example"},
                 {"@type":"NewsArticle","headline":"Second entry"}]
                </script>
                """);

        assertThat(article.getHeadline()).isEqualTo("Second entry");
    }

    @Test
    void read_imageAsObjectAndAsArray_yieldsUrls() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":"NewsArticle","image":[
                  {"@type":"ImageObject","url":"https://example.test/a.jpg"},
                  "https://example.test/b.jpg"]}
                </script>
                """);

        assertThat(article.getImages())
                .containsExactly("https://example.test/a.jpg", "https://example.test/b.jpg");
    }

    @Test
    void read_authorAsList_isJoined() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":"NewsArticle","author":[
                  {"@type":"Person","name":"A Writer"},{"@type":"Person","name":"B Writer"}]}
                </script>
                """);

        assertThat(article.getAuthor()).isEqualTo("A Writer, B Writer");
    }

    @Test
    void read_authorAsBareString_isAccepted() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":"NewsArticle","author":"Newsroom"}
                </script>
                """);

        assertThat(article.getAuthor()).isEqualTo("Newsroom");
    }

    @Test
    void read_malformedBlock_isSkippedAndTheNextOneWins() {
        // Template-generated JSON-LD is broken often enough that one bad
        // block must not cost us the page.
        JsonLdArticle article = read("""
                <script type="application/ld+json">{ this is not json }</script>
                <script type="application/ld+json">
                {"@type":"NewsArticle","headline":"Second block"}
                </script>
                """);

        assertThat(article.getHeadline()).isEqualTo("Second block");
    }

    @Test
    void read_nonArticleTypes_areIgnored() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":"Recipe","headline":"Not news"}
                </script>
                """);

        assertThat(article.isPresent()).isFalse();
    }

    @Test
    void read_unparseableDate_leavesTheFieldEmptyRatherThanGuessing() {
        // A local date-time without an offset would have to be assigned a
        // time zone, which would put the article in the wrong hour.
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":"NewsArticle","datePublished":"2026-08-18 09:30"}
                </script>
                """);

        assertThat(article.isPresent()).isTrue();
        assertThat(article.getDatePublished()).isNull();
    }

    @Test
    void read_articleBody_keepsParagraphBreaksButCollapsesRuns() {
        JsonLdArticle article = read("""
                <script type="application/ld+json">
                {"@type":"NewsArticle","articleBody":"One   two.\\n\\n\\n\\nThree    four."}
                </script>
                """);

        assertThat(article.getArticleBody()).isEqualTo("One two.\n\nThree four.");
    }
}
