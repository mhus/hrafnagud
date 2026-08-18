package de.mhus.hrafnagud.munin.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.munin.article.ArticleCandidate;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises the mapping decisions, not Rome's parsing: which element holds
 * the link, which field holds the teaser, where the image is, and which
 * entries are unusable. Those are where feeds disagree with each other.
 */
@ExtendWith(MockitoExtension.class)
class RssSourceReaderTest {

    @Mock
    private HttpFetcher fetcher;

    private RssSourceReader reader;

    @BeforeEach
    void setUp() {
        reader = new RssSourceReader(fetcher, new MuninProperties());
    }

    private static SourceDocument source() {
        return SourceDocument.builder()
                .name("example-abc123")
                .url("https://example.com/rss")
                .build();
    }

    private void respondWith(String body) {
        when(fetcher.get(eq("https://example.com/rss"), any(), any()))
                .thenReturn(HttpFetchResult.builder()
                        .status(200)
                        .body(body.getBytes(StandardCharsets.UTF_8))
                        .contentType("application/rss+xml")
                        .finalUrl("https://example.com/rss")
                        .etag("\"abc\"")
                        .build());
    }

    @Test
    void read_rss20_mapsEntries() {
        respondWith("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Example News</title>
                    <language>de-DE</language>
                    <item>
                      <title>Rat beschliesst Plan</title>
                      <link>https://example.com/story?utm_source=rss</link>
                      <description>&lt;p&gt;Die Abstimmung beendet eine Debatte.&lt;/p&gt;</description>
                      <pubDate>Tue, 18 Aug 2026 09:00:00 GMT</pubDate>
                      <author>redaktion@example.com</author>
                      <category>Politik</category>
                    </item>
                  </channel>
                </rss>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getOutcome()).isEqualTo(FetchOutcome.OK);
        assertThat(result.getFeedLanguage()).isEqualTo("de");
        assertThat(result.getFeedTitle()).isEqualTo("Example News");
        assertThat(result.getCandidates()).hasSize(1);

        ArticleCandidate candidate = result.getCandidates().getFirst();
        assertThat(candidate.getTitle()).isEqualTo("Rat beschliesst Plan");
        // Tracking parameters are stripped from the identity but the raw
        // link is kept for provenance.
        assertThat(candidate.getUrl()).isEqualTo("https://example.com/story");
        assertThat(candidate.getOriginalUrl()).contains("utm_source=rss");
        assertThat(candidate.getSummary()).isEqualTo("Die Abstimmung beendet eine Debatte.");
        assertThat(candidate.getCategories()).containsExactly("Politik");
        assertThat(candidate.getPublishedAt())
                .isEqualTo(Instant.parse("2026-08-18T09:00:00Z"));
        assertThat(candidate.getDeclaredLanguage()).isEqualTo("de");
    }

    @Test
    void read_atom_mapsEntriesFromTheAlternateLink() {
        respondWith("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Example</title>
                  <entry>
                    <title>Council approves plan</title>
                    <id>tag:example.com,2026:story-1</id>
                    <link rel="alternate" href="https://example.com/story"/>
                    <summary>Short teaser.</summary>
                    <content type="html">&lt;p&gt;A considerably longer body text.&lt;/p&gt;</content>
                    <updated>2026-08-18T09:00:00Z</updated>
                  </entry>
                </feed>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getCandidates()).hasSize(1);
        ArticleCandidate candidate = result.getCandidates().getFirst();
        assertThat(candidate.getUrl()).isEqualTo("https://example.com/story");
        // The richer of description and content wins, whichever dialect it
        // came from.
        assertThat(candidate.getSummary()).isEqualTo("A considerably longer body text.");
    }

    @Test
    void read_nonPermalinkIdentifier_isNotUsedAsTheLink() {
        // An Atom id is an identifier, not necessarily a location. Preferring
        // it would store identifiers as article URLs.
        respondWith("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>No link here</title>
                    <id>tag:example.com,2026:story-1</id>
                    <summary>Teaser.</summary>
                  </entry>
                </feed>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getCandidates()).isEmpty();
        assertThat(result.getInvalidCount()).isEqualTo(1);
    }

    @Test
    void read_entryWithoutATitle_isRejected() {
        respondWith("""
                <rss version="2.0"><channel><item>
                  <link>https://example.com/story</link>
                  <description>Body without a headline.</description>
                </item></channel></rss>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getCandidates()).isEmpty();
        assertThat(result.getInvalidCount()).isEqualTo(1);
    }

    @Test
    void read_imageEnclosure_becomesTheLeadImage() {
        respondWith("""
                <rss version="2.0"><channel><item>
                  <title>With image</title>
                  <link>https://example.com/story</link>
                  <enclosure url="https://example.com/lead.jpg" type="image/jpeg" length="1"/>
                </item></channel></rss>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getCandidates().getFirst().getImageUrl())
                .isEqualTo("https://example.com/lead.jpg");
    }

    @Test
    void read_nonImageEnclosure_fallsBackToAnInlineImage() {
        respondWith("""
                <rss version="2.0"><channel><item>
                  <title>Podcast episode</title>
                  <link>https://example.com/story</link>
                  <enclosure url="https://example.com/audio.mp3" type="audio/mpeg" length="1"/>
                  <description>&lt;img src="https://example.com/cover.png"/&gt;Text</description>
                </item></channel></rss>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getCandidates().getFirst().getImageUrl())
                .isEqualTo("https://example.com/cover.png");
    }

    @Test
    void read_implausiblePublicationDate_isDropped() {
        respondWith("""
                <rss version="2.0"><channel><item>
                  <title>From the future</title>
                  <link>https://example.com/story</link>
                  <pubDate>Thu, 01 Jan 2099 00:00:00 GMT</pubDate>
                </item></channel></rss>
                """);

        SourceReadResult result = reader.read(source());

        assertThat(result.getCandidates().getFirst().getPublishedAt()).isNull();
    }

    @Test
    void read_notModified_returnsWithoutCandidates() {
        when(fetcher.get(eq("https://example.com/rss"), any(), any()))
                .thenReturn(HttpFetchResult.builder()
                        .status(304)
                        .body(new byte[0])
                        .finalUrl("https://example.com/rss")
                        .etag("\"abc\"")
                        .build());

        SourceReadResult result = reader.read(source());

        assertThat(result.getOutcome()).isEqualTo(FetchOutcome.NOT_MODIFIED);
        assertThat(result.getCandidates()).isEmpty();
        assertThat(result.getEtag()).isEqualTo("\"abc\"");
    }

    @Test
    void read_transportFailure_isAFetchError() {
        when(fetcher.get(eq("https://example.com/rss"), any(), any()))
                .thenReturn(HttpFetchResult.builder()
                        .status(0)
                        .body(new byte[0])
                        .finalUrl("https://example.com/rss")
                        .error("ConnectException: refused")
                        .build());

        SourceReadResult result = reader.read(source());

        assertThat(result.getOutcome()).isEqualTo(FetchOutcome.FETCH_ERROR);
        assertThat(result.getError()).contains("refused");
    }

    @Test
    void read_htmlWhereAFeedWasExpected_isAParseError() {
        // Distinguished from a fetch error because backoff will never fix
        // it — the URL is wrong and a human has to look.
        respondWith("<html><body><h1>Login required</h1></body></html>");

        SourceReadResult result = reader.read(source());

        assertThat(result.getOutcome()).isEqualTo(FetchOutcome.PARSE_ERROR);
    }
}
