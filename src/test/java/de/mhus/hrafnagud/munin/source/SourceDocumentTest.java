package de.mhus.hrafnagud.munin.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one decision the document itself makes: which of its two URLs to poll. */
class SourceDocumentTest {

    private static SourceDocument withFetchUrl(String fetchUrl) {
        return SourceDocument.builder()
                .url("https://the42.ie/feed")
                .fetchUrl(fetchUrl)
                .build();
    }

    @Test
    void withoutAResolvedLocation_theIdentityIsPolled() {
        assertThat(withFetchUrl(null).effectiveUrl()).isEqualTo("https://the42.ie/feed");
    }

    @Test
    void withOne_thatIsPolled() {
        assertThat(withFetchUrl("https://www.the42.ie/feed/").effectiveUrl())
                .isEqualTo("https://www.the42.ie/feed/");
    }

    @Test
    void blank_countsAsAbsent() {
        // Belt and braces: an empty string reaching the fetcher would be a
        // malformed-URL failure on a source that is perfectly fine.
        assertThat(withFetchUrl("").effectiveUrl()).isEqualTo("https://the42.ie/feed");
        assertThat(withFetchUrl("   ").effectiveUrl()).isEqualTo("https://the42.ie/feed");
    }
}
