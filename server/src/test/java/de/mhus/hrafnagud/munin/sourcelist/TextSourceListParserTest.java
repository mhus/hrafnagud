package de.mhus.hrafnagud.munin.sourcelist;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.munin.source.SourceCandidate;
import org.junit.jupiter.api.Test;

class TextSourceListParserTest {

    private final TextSourceListParser parser = new TextSourceListParser();

    @Test
    void parse_readsOneUrlPerLine() {
        ParsedSourceList parsed = parser.parse("""
                https://a.example/rss
                https://b.example/rss
                """, 1000);

        assertThat(parsed.getEntries()).extracting(SourceCandidate::getUrl)
                .containsExactly("https://a.example/rss", "https://b.example/rss");
    }

    @Test
    void parse_takesAnOptionalTitleAfterTheUrl() {
        ParsedSourceList parsed = parser.parse("https://a.example/rss  Example News", 1000);

        assertThat(parsed.getEntries().getFirst().getTitle()).isEqualTo("Example News");
    }

    @Test
    void parse_withoutATitle_fallsBackToTheHost() {
        ParsedSourceList parsed = parser.parse("https://a.example/rss", 1000);

        assertThat(parsed.getEntries().getFirst().getTitle()).isEqualTo("a.example");
    }

    @Test
    void parse_ignoresCommentsAndBlankLines() {
        ParsedSourceList parsed = parser.parse("""
                # a plain comment

                https://a.example/rss
                """, 1000);

        assertThat(parsed.getEntries()).hasSize(1);
    }

    @Test
    void parse_categoryDirective_appliesToFollowingEntriesOnly() {
        ParsedSourceList parsed = parser.parse("""
                https://before.example/rss
                # category: Politics
                https://a.example/rss
                # category: Sport
                https://b.example/rss
                """, 1000);

        assertThat(parsed.getEntries()).hasSize(3);
        assertThat(parsed.getEntries().get(0).getCategories()).isEmpty();
        assertThat(parsed.getEntries().get(1).getCategories()).containsExactly("Politics");
        assertThat(parsed.getEntries().get(2).getCategories()).containsExactly("Sport");
    }

    @Test
    void parse_emptyCategoryDirective_clearsTheCategory() {
        ParsedSourceList parsed = parser.parse("""
                # category: Politics
                https://a.example/rss
                # category:
                https://b.example/rss
                """, 1000);

        assertThat(parsed.getEntries().get(1).getCategories()).isEmpty();
    }

    @Test
    void parse_reportsUnusableLinesWithTheirLineNumber() {
        ParsedSourceList parsed = parser.parse("""
                https://a.example/rss
                not-a-url
                """, 1000);

        assertThat(parsed.getEntries()).hasSize(1);
        assertThat(parsed.getInvalidCount()).isEqualTo(1);
        assertThat(parsed.getWarnings().getFirst()).contains("line 2");
    }

    @Test
    void parse_deduplicatesRepeatedUrls() {
        ParsedSourceList parsed = parser.parse("""
                https://a.example/rss
                https://www.a.example/rss/
                """, 1000);

        // Both spellings normalise to the same feed.
        assertThat(parsed.getEntries()).hasSize(1);
    }

    @Test
    void parse_honoursTheEntryLimit() {
        ParsedSourceList parsed = parser.parse("""
                https://a.example/rss
                https://b.example/rss
                https://c.example/rss
                """, 2);

        assertThat(parsed.getEntries()).hasSize(2);
    }
}
