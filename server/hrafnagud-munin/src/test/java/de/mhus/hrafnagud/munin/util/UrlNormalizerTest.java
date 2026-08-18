package de.mhus.hrafnagud.munin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Normalisation decides article identity, so these tests are really about
 * two failure modes: over-normalising merges distinct articles and loses
 * content, under-normalising fills the archive with duplicates.
 */
class UrlNormalizerTest {

    @Test
    void normalize_trackingParameters_areDropped() {
        assertThat(UrlNormalizer.normalize(
                "https://example.com/news/story?utm_source=twitter&utm_medium=social&id=42"))
                .contains("https://example.com/news/story?id=42");
    }

    @Test
    void normalize_clickIdentifiers_areDropped() {
        assertThat(UrlNormalizer.normalize("https://example.com/a?fbclid=XYZ&gclid=ABC"))
                .contains("https://example.com/a");
    }

    @Test
    void normalize_trackingPrefixFamilies_areDropped() {
        assertThat(UrlNormalizer.normalize(
                "https://example.com/a?at_campaign=x&pk_source=y&mtm_medium=z&keep=1"))
                .contains("https://example.com/a?keep=1");
    }

    @Test
    void normalize_reorderedQueryParameters_yieldTheSameUrl() {
        Optional<String> first = UrlNormalizer.normalize("https://example.com/a?b=2&a=1");
        Optional<String> second = UrlNormalizer.normalize("https://example.com/a?a=1&b=2");
        assertThat(first).isPresent().isEqualTo(second);
    }

    @Test
    void normalize_fragment_isDropped() {
        assertThat(UrlNormalizer.normalize("https://example.com/story#comments"))
                .contains("https://example.com/story");
    }

    @Test
    void normalize_hostCaseAndWwwAndDefaultPort_areFolded() {
        assertThat(UrlNormalizer.normalize("https://WWW.Example.COM:443/Story"))
                .contains("https://example.com/Story");
    }

    @Test
    void normalize_pathCase_isPreserved() {
        // Hosts are case-insensitive, paths are not. Lowercasing the path
        // would merge two genuinely different documents on any case-sensitive
        // server.
        assertThat(UrlNormalizer.normalize("https://example.com/Story/AbC"))
                .contains("https://example.com/Story/AbC");
    }

    @Test
    void normalize_trailingSlash_isDroppedExceptAtRoot() {
        assertThat(UrlNormalizer.normalize("https://example.com/news/"))
                .contains("https://example.com/news");
        assertThat(UrlNormalizer.normalize("https://example.com/"))
                .contains("https://example.com/");
    }

    @Test
    void normalize_ampVariants_collapseOntoTheCanonicalUrl() {
        assertThat(UrlNormalizer.normalize("https://example.com/news/story/amp"))
                .contains("https://example.com/news/story");
        assertThat(UrlNormalizer.normalize("https://example.com/news/story?outputType=amp"))
                .contains("https://example.com/news/story");
    }

    @Test
    void normalize_pathEndingInAmpAsAWord_isNotTruncated() {
        // "/bands/amp" would be mangled by a naive suffix strip; the guard is
        // that only a whole trailing segment counts.
        assertThat(UrlNormalizer.normalize("https://example.com/lamp"))
                .contains("https://example.com/lamp");
    }

    @Test
    void normalize_internationalisedHost_isPunycoded() {
        assertThat(UrlNormalizer.normalize("https://münchen.example/nachricht"))
                .contains("https://xn--mnchen-3ya.example/nachricht");
    }

    @Test
    void normalize_nonLatinHost_isPunycoded() {
        assertThat(UrlNormalizer.normalize("https://пример.рф/новости"))
                .get().asString().startsWith("https://xn--");
    }

    @Test
    void normalize_unencodedNonAsciiPath_isPercentEncoded() {
        // Publishers serve these unencoded routinely. Rejecting them would
        // drop a large share of the non-English web.
        assertThat(UrlNormalizer.normalize("https://example.com/über-uns"))
                .contains("https://example.com/%C3%BCber-uns");
    }

    @Test
    void normalize_alreadyEncodedPath_isLeftAlone() {
        assertThat(UrlNormalizer.normalize("https://example.com/%C3%BCber-uns"))
                .contains("https://example.com/%C3%BCber-uns");
    }

    @Test
    void normalize_illegalAsciiCharacters_areEncodedRatherThanRejected() {
        assertThat(UrlNormalizer.normalize("https://example.com/a|b"))
                .contains("https://example.com/a%7Cb");
    }

    @Test
    void normalize_schemeRelativeUrl_getsHttps() {
        assertThat(UrlNormalizer.normalize("//example.com/story"))
                .contains("https://example.com/story");
    }

    @Test
    void normalize_bareHostWithPath_getsHttps() {
        assertThat(UrlNormalizer.normalize("example.com/story"))
                .contains("https://example.com/story");
    }

    @Test
    void normalize_embeddedNewline_isRepaired() {
        // Feeds wrap long links; the parser hands them over with the break.
        assertThat(UrlNormalizer.normalize("https://example.com/very\n  /long"))
                .contains("https://example.com/very/long");
    }

    @Test
    void normalize_nonHttpSchemes_areRejected() {
        assertThat(UrlNormalizer.normalize("mailto:editor@example.com")).isEmpty();
        assertThat(UrlNormalizer.normalize("javascript:void(0)")).isEmpty();
        assertThat(UrlNormalizer.normalize("ftp://example.com/file")).isEmpty();
    }

    @Test
    void normalize_blankAndRelative_areRejected() {
        assertThat(UrlNormalizer.normalize("")).isEmpty();
        assertThat(UrlNormalizer.normalize("   ")).isEmpty();
        assertThat(UrlNormalizer.normalize("/relative/path")).isEmpty();
    }

    @Test
    void normalize_percentEncodedValues_areNotReEncoded() {
        // A decode/re-encode round trip is how %2B silently becomes a space
        // and two equivalent URLs stop matching.
        assertThat(UrlNormalizer.normalize("https://example.com/s?q=a%2Bb"))
                .contains("https://example.com/s?q=a%2Bb");
    }

    @Test
    void normalizeOrRaw_keepsUnparseableInputInsteadOfLosingIt() {
        assertThat(UrlNormalizer.normalizeOrRaw("not a url at all")).isEqualTo("not a url at all");
        assertThat(UrlNormalizer.normalizeOrRaw("https://WWW.example.com/x"))
                .isEqualTo("https://example.com/x");
    }
}
