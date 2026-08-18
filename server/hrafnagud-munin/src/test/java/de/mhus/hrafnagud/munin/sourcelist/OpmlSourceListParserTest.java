package de.mhus.hrafnagud.munin.sourcelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.hrafnagud.munin.source.SourceCandidate;
import org.junit.jupiter.api.Test;

/**
 * The fixtures mirror the real shape of the awesome-rss-feeds directory —
 * a wrapping category outline holding feed outlines with {@code xmlUrl} —
 * because that is the document this parser exists to read.
 */
class OpmlSourceListParserTest {

    private final OpmlSourceListParser parser = new OpmlSourceListParser();

    private static final String GERMANY_OPML = """
            <?xml version='1.0' encoding='UTF-8' ?>
            <opml version="1.0">
              <head><title>Export from Plenary</title></head>
              <body>
                <outline text="Germany" title="Germany">
                  <outline text="ZEIT ONLINE" title="ZEIT ONLINE" description="Nachrichten"
                           xmlUrl="http://newsfeed.zeit.de/index" type="rss" />
                  <outline text="tagesschau.de" title="tagesschau.de"
                           xmlUrl="http://www.tagesschau.de/xml/rss2" type="rss" />
                </outline>
              </body>
            </opml>
            """;

    @Test
    void parse_readsFeedsAndTheListTitle() {
        ParsedSourceList parsed = parser.parse(GERMANY_OPML, 1000);

        assertThat(parsed.getTitle()).isEqualTo("Export from Plenary");
        assertThat(parsed.getEntries()).hasSize(2);
        assertThat(parsed.getEntries()).extracting(SourceCandidate::getTitle)
                .containsExactly("ZEIT ONLINE", "tagesschau.de");
    }

    @Test
    void parse_normalisesFeedUrls() {
        ParsedSourceList parsed = parser.parse(GERMANY_OPML, 1000);

        // The www. prefix is folded, so the same feed listed both ways is
        // one source rather than two.
        assertThat(parsed.getEntries()).extracting(SourceCandidate::getUrl)
                .containsExactly("http://newsfeed.zeit.de/index",
                        "http://tagesschau.de/xml/rss2");
    }

    @Test
    void parse_takesTheEnclosingOutlineLabelAsCategory() {
        ParsedSourceList parsed = parser.parse(GERMANY_OPML, 1000);

        assertThat(parsed.getEntries()).allSatisfy(entry ->
                assertThat(entry.getCategories()).containsExactly("Germany"));
    }

    @Test
    void parse_collectsTheWholeAncestorChainAsCategories() {
        ParsedSourceList parsed = parser.parse("""
                <opml version="1.0"><body>
                  <outline text="News">
                    <outline text="Europe">
                      <outline text="Le Monde" xmlUrl="https://lemonde.fr/rss" type="rss"/>
                    </outline>
                  </outline>
                </body></opml>
                """, 1000);

        assertThat(parsed.getEntries()).hasSize(1);
        assertThat(parsed.getEntries().getFirst().getCategories())
                .containsExactly("News", "Europe");
    }

    @Test
    void parse_deduplicatesAFeedListedUnderSeveralFolders() {
        // Directories do this routinely. The first occurrence keeps its
        // categories, and the repeat is not counted as invalid — it is an
        // ordinary situation, not a defect in the document.
        ParsedSourceList parsed = parser.parse("""
                <opml version="1.0"><body>
                  <outline text="Tech">
                    <outline text="Feed" xmlUrl="https://example.com/rss" type="rss"/>
                  </outline>
                  <outline text="Science">
                    <outline text="Feed" xmlUrl="https://example.com/rss" type="rss"/>
                  </outline>
                </body></opml>
                """, 1000);

        assertThat(parsed.getEntries()).hasSize(1);
        assertThat(parsed.getEntries().getFirst().getCategories()).containsExactly("Tech");
        assertThat(parsed.getInvalidCount()).isZero();
    }

    @Test
    void parse_rejectsUnusableUrlsWithoutLosingTheGoodEntries() {
        ParsedSourceList parsed = parser.parse("""
                <opml version="1.0"><body>
                  <outline text="Bad" xmlUrl="javascript:void(0)" type="rss"/>
                  <outline text="Good" xmlUrl="https://example.com/rss" type="rss"/>
                  <outline text="Empty" xmlUrl="" type="rss"/>
                </body></opml>
                """, 1000);

        assertThat(parsed.getEntries()).hasSize(1);
        assertThat(parsed.getInvalidCount()).isEqualTo(1);
        assertThat(parsed.getWarnings()).hasSize(1);
    }

    @Test
    void parse_readsHtmlUrlAsTheSiteUrl() {
        ParsedSourceList parsed = parser.parse("""
                <opml version="1.0"><body>
                  <outline text="Feed" xmlUrl="https://example.com/rss"
                           htmlUrl="https://example.com/" type="rss"/>
                </body></opml>
                """, 1000);

        assertThat(parsed.getEntries().getFirst().getSiteUrl()).isEqualTo("https://example.com/");
    }

    @Test
    void parse_fallsBackToTheTitleAttributeWhenTextIsAbsent() {
        ParsedSourceList parsed = parser.parse("""
                <opml version="1.0"><body>
                  <outline title="Only Title" xmlUrl="https://example.com/rss" type="rss"/>
                </body></opml>
                """, 1000);

        assertThat(parsed.getEntries().getFirst().getTitle()).isEqualTo("Only Title");
    }

    @Test
    void parse_honoursTheEntryLimit() {
        ParsedSourceList parsed = parser.parse("""
                <opml version="1.0"><body>
                  <outline text="A" xmlUrl="https://a.example/rss" type="rss"/>
                  <outline text="B" xmlUrl="https://b.example/rss" type="rss"/>
                  <outline text="C" xmlUrl="https://c.example/rss" type="rss"/>
                </body></opml>
                """, 2);

        assertThat(parsed.getEntries()).hasSize(2);
    }

    @Test
    void parse_ofAnHtmlErrorPage_failsAsAWhole() {
        // Directories go behind login walls and 404 pages. That is a
        // configuration problem, not a document with bad rows in it.
        assertThatThrownBy(() -> parser.parse("<html><body>Not found</body></html>", 1000))
                .isInstanceOf(SourceListParseException.class)
                .hasMessageContaining("no <opml>");
    }

    @Test
    void parse_ofAnEmptyBody_yieldsNoEntriesRatherThanFailing() {
        ParsedSourceList parsed = parser.parse("<opml version=\"1.0\"><body/></opml>", 1000);
        assertThat(parsed.getEntries()).isEmpty();
        assertThat(parsed.getInvalidCount()).isZero();
    }
}
