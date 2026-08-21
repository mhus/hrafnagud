package de.mhus.hrafnagud.munin.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The derived address. Its whole value is that two callers computing it
 * independently agree, so these tests pin the properties that make that true.
 */
class ImageKeyTest {

    private static final String URL = "https://images.example.com/2026/08/photo.jpg?v=3";

    @Test
    void sameUrl_yieldsSameKey() {
        assertThat(ImageKey.of(URL)).isEqualTo(ImageKey.of(URL));
    }

    @Test
    void differentUrl_yieldsDifferentKey() {
        assertThat(ImageKey.of(URL)).isNotEqualTo(ImageKey.of(URL + "0"));
    }

    @Test
    void key_isLowercaseHexOfFixedLength() {
        // It ends up in a Mongo _id and, later, in a mount path — both want a
        // fixed-length token with nothing in it that needs escaping.
        assertThat(ImageKey.of(URL)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void queryStringMatters() {
        // Two URLs differing only in a cache-busting parameter are two images
        // as far as the publisher is concerned, and normalising them together
        // here would disagree with the URL the article stored.
        assertThat(ImageKey.of("https://e.com/a.jpg?v=1"))
                .isNotEqualTo(ImageKey.of("https://e.com/a.jpg?v=2"));
    }

    /**
     * Pinned against an external {@code shasum -a 256}, not against itself.
     * Every stored image is addressed by this value; changing it silently
     * would make the whole collection unreachable while every test that
     * compares the function to itself stayed green.
     */
    @Test
    void key_isTheSha256OfTheUrlBytes() {
        assertThat(ImageKey.of("https://example.com/a.jpg"))
                .isEqualTo("276a1ac00ba4f0ea47eeeafca24284f41bc78dc593af1f048615aceba44ab9d9");
        assertThat(ImageKey.of("https://example.com/a.jpg")).isEqualTo(
                ImageKey.ofBytes("https://example.com/a.jpg".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void contentHash_differsWithTheBytes() {
        assertThat(ImageKey.ofBytes(new byte[] {1, 2, 3}))
                .isNotEqualTo(ImageKey.ofBytes(new byte[] {1, 2, 4}))
                .hasSize(64);
    }
}
