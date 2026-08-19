package de.mhus.hrafnagud.munin.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The standard-compliant reader.
 *
 * <p>The cases here are the ones the OPML 2.0 spec actually distinguishes:
 * {@code include} always points at OPML, {@code link} only counts when the
 * address ends in {@code .opml} — a {@code link} to a web page is for a
 * browser and must not become a source list.
 */
class OpmlDirectoryReaderTest {

    private HttpFetcher fetcher;
    private OpmlDirectoryReader reader;

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpFetcher.class);
        reader = new OpmlDirectoryReader(fetcher);
    }

    @Test
    void include_and_opml_links_both_become_entries() {
        serve("""
                <opml version="2.0"><head><title>Dir</title></head><body>
                  <outline type="include" text="News" url="https://x.test/news.opml"/>
                  <outline type="link" text="Sport" url="https://x.test/sport.opml"/>
                </body></opml>""");

        CatalogReadResult result = reader.read(catalog());

        assertThat(result.entries()).extracting(CatalogEntry::url)
                .containsExactly("https://x.test/news.opml", "https://x.test/sport.opml");
        assertThat(result.entries()).extracting(CatalogEntry::type)
                .containsOnly(SourceListType.OPML);
    }

    /** "If the address does not end with .opml the link is assumed to point to
     * something that can be displayed in a web browser." */
    @Test
    void a_link_to_a_web_page_is_not_an_entry() {
        serve("""
                <opml version="2.0"><body>
                  <outline type="link" text="Homepage" url="https://x.test/"/>
                  <outline type="include" text="News" url="https://x.test/news.opml"/>
                </body></opml>""");

        assertThat(reader.read(catalog()).entries()).hasSize(1);
    }

    /** A subscription list is not a directory: feeds are the next layer's job. */
    @Test
    void rss_outlines_are_ignored() {
        serve("""
                <opml version="2.0"><body>
                  <outline type="rss" text="A feed" xmlUrl="https://x.test/feed.xml"/>
                </body></opml>""");

        CatalogReadResult result = reader.read(catalog());

        assertThat(result.entries()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("no OPML entries"));
    }

    @Test
    void folder_labels_become_the_key_and_the_categories() {
        serve("""
                <opml version="2.0"><body>
                  <outline text="Europe">
                    <outline type="include" text="Germany" url="https://x.test/de.opml"/>
                  </outline>
                </body></opml>""");

        CatalogEntry entry = reader.read(catalog()).entries().getFirst();

        assertThat(entry.key()).isEqualTo("Europe/Germany");
        assertThat(entry.categories()).containsExactly("Europe");
    }

    @Test
    void the_same_url_twice_is_one_entry() {
        serve("""
                <opml version="2.0"><body>
                  <outline type="include" text="A" url="https://x.test/a.opml"/>
                  <outline type="include" text="A again" url="https://x.test/a.opml"/>
                </body></opml>""");

        assertThat(reader.read(catalog()).entries()).hasSize(1);
    }

    /**
     * An empty directory is a fact, not a failure — throwing would make the
     * caller keep the old entries, and the publisher may have meant it.
     */
    @Test
    void an_empty_directory_is_read_successfully_with_a_warning() {
        serve("<opml version=\"2.0\"><body></body></opml>");

        CatalogReadResult result = reader.read(catalog());

        assertThat(result.entries()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void something_that_is_not_opml_is_a_read_failure() {
        serve("<html><body>404</body></html>");

        assertThatThrownBy(() -> reader.read(catalog()))
                .isInstanceOf(CatalogReadException.class)
                .hasMessageContaining("<opml>");
    }

    @Test
    void an_http_failure_is_a_read_failure() {
        when(fetcher.get(anyString())).thenReturn(HttpFetchResult.builder()
                .status(503).error("Service Unavailable").build());

        assertThatThrownBy(() -> reader.read(catalog()))
                .isInstanceOf(CatalogReadException.class);
    }

    /** Ordering must not change the fingerprint, or every refresh looks like a change. */
    @Test
    void the_fingerprint_is_order_independent() {
        CatalogEntry a = CatalogEntry.of("a", "https://x.test/a.opml", "A");
        CatalogEntry b = CatalogEntry.of("b", "https://x.test/b.opml", "B");

        assertThat(CatalogReadResult.of(List.of(a, b)).fingerprint())
                .isEqualTo(CatalogReadResult.of(List.of(b, a)).fingerprint());
    }

    @Test
    void the_fingerprint_changes_when_an_entry_does() {
        CatalogEntry a = CatalogEntry.of("a", "https://x.test/a.opml", "A");
        CatalogEntry b = CatalogEntry.of("b", "https://x.test/b.opml", "B");

        assertThat(CatalogReadResult.of(List.of(a)).fingerprint())
                .isNotEqualTo(CatalogReadResult.of(List.of(a, b)).fingerprint());
    }

    private void serve(String body) {
        when(fetcher.get(anyString())).thenReturn(HttpFetchResult.builder()
                .status(200)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .build());
    }

    private static SourceCatalogDocument catalog() {
        return SourceCatalogDocument.builder()
                .name("dir")
                .type(OpmlDirectoryReader.TYPE)
                .url("https://x.test/directory.opml")
                .build();
    }
}
