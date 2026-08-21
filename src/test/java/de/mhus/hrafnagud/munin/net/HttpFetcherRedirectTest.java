package de.mhus.hrafnagud.munin.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The redirect the HTTP client refuses, and what we do about it.
 *
 * <p>Tested as a function rather than against a server for the same reason
 * {@code proxySelector} is: the decision is the whole content, and reaching
 * it through TLS would need a certificate authority to test a string
 * transformation. What a server would add — that the second request is
 * actually sent — is checked in {@code RssSourceReaderTest} and against the
 * live feed that prompted this.
 */
class HttpFetcherRedirectTest {

    private static Optional<URI> repair(String from, int status, String location) {
        return HttpFetcher.repairTarget(URI.create(from), status, location);
    }

    /**
     * The case this exists for, as the42.ie serves it: the client follows
     * {@code the42.ie → www.the42.ie} on its own, then stops at a hop to
     * plain HTTP, and the URL that answers is that target over HTTPS.
     */
    @Test
    void downgrade_isRetriedOverHttps() {
        assertThat(repair("https://www.the42.ie/feed", 301, "http://www.the42.ie/feed/"))
                .contains(URI.create("https://www.the42.ie/feed/"));
    }

    @Test
    void relativeLocation_cannotBeADowngrade() {
        // It inherits the scheme of whoever sent it, so the client follows it.
        assertThat(repair("https://www.example.com/news/feed", 301, "/rss"))
                .isEmpty();
    }

    @Test
    void sameSchemeRedirect_isLeftToTheClient() {
        // NORMAL already follows these, so a 301 to https means the client
        // is mid-chain and there is nothing here to do.
        assertThat(repair("https://example.com/feed", 301, "https://www.example.com/feed"))
                .isEmpty();
    }

    @Test
    void plainHttpSource_isNotADowngrade() {
        assertThat(repair("http://example.com/feed", 301, "http://www.example.com/feed"))
                .isEmpty();
    }

    @Test
    void explicitPort80_isDroppedWithTheScheme() {
        // Upgrading the scheme but keeping the port would ask for TLS on the
        // cleartext port — a connection refused, reported as the source's
        // problem.
        assertThat(repair("https://example.com/feed", 301, "http://example.com:80/feed/"))
                .contains(URI.create("https://example.com/feed/"));
    }

    @Test
    void otherPorts_areKept() {
        assertThat(repair("https://example.com/feed", 301, "http://example.com:8080/feed/"))
                .contains(URI.create("https://example.com:8080/feed/"));
    }

    @Test
    void queryAndEncoding_surviveTheUpgrade() {
        assertThat(repair("https://example.com/feed", 308,
                "http://example.com/a%20b/feed?tag=x%26y&n=1"))
                .contains(URI.create("https://example.com/a%20b/feed?tag=x%26y&n=1"));
    }

    @Test
    void fragment_isDropped() {
        // It never travels to the server anyway.
        assertThat(repair("https://example.com/feed", 301, "http://example.com/feed#top"))
                .contains(URI.create("https://example.com/feed"));
    }

    @Test
    void noLocation_isNothingToRepair() {
        assertThat(repair("https://example.com/feed", 301, "")).isEmpty();
        assertThat(HttpFetcher.repairTarget(URI.create("https://example.com/feed"), 301, null))
                .isEmpty();
    }

    @Test
    void nonRedirectStatus_isNothingToRepair() {
        assertThat(repair("https://example.com/feed", 200, "http://example.com/feed")).isEmpty();
        assertThat(repair("https://example.com/feed", 404, "http://example.com/feed")).isEmpty();
        assertThat(repair("https://example.com/feed", 403, "http://example.com/feed")).isEmpty();
    }

    /**
     * 304 shares the hundred with the redirects and means the opposite: the
     * cheapest successful poll there is. It must not be read as one, or every
     * unchanged feed acquires a complaint about the Location header it had no
     * reason to send.
     */
    @Test
    void notModified_isNotARedirect() {
        assertThat(repair("https://example.com/feed", 304, "http://example.com/feed")).isEmpty();
    }

    @Test
    void schemeRelativeLocation_keepsTheOriginalScheme() {
        // "//host/path" inherits https from the responding URL, so the client
        // follows it and we stay out of the way.
        assertThat(repair("https://example.com/feed", 301, "//www.example.com/feed"))
                .isEmpty();
    }

    @Test
    void unparseableLocation_yieldsNoTarget() {
        assertThat(repair("https://example.com/feed", 301, "http://exa mple.com/feed")).isEmpty();
        assertThat(repair("https://example.com/feed", 301, ":::")).isEmpty();
    }

    @Test
    void mailtoLocation_isNotFetched() {
        assertThat(repair("https://example.com/feed", 301, "mailto:editor@example.com")).isEmpty();
    }

    /**
     * A temporary redirect is followed like any other but never remembered:
     * only 301 and 308 say the old URL is finished.
     */
    @Test
    void onlyPermanentStatusesAreWorthStoring() {
        assertThat(HttpFetcher.isPermanentRedirect(301)).isTrue();
        assertThat(HttpFetcher.isPermanentRedirect(308)).isTrue();
        assertThat(HttpFetcher.isPermanentRedirect(302)).isFalse();
        assertThat(HttpFetcher.isPermanentRedirect(303)).isFalse();
        assertThat(HttpFetcher.isPermanentRedirect(307)).isFalse();
        assertThat(HttpFetcher.isPermanentRedirect(200)).isFalse();
    }
}
