package de.mhus.hrafnagud.munin.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

    @Test
    void sourceName_isReadableAndDerivedFromTheHost() {
        assertThat(Slugs.sourceName("https://www.spiegel.de/schlagzeilen/index.rss"))
                .startsWith("www-spiegel-de-");
    }

    @Test
    void sourceName_isStableAcrossCalls() {
        String url = "https://example.com/feed.xml";
        assertThat(Slugs.sourceName(url)).isEqualTo(Slugs.sourceName(url));
    }

    @Test
    void sourceName_differsPerFeedOnTheSameHost() {
        // One publisher serves dozens of feeds; the host alone cannot be the
        // key or they would all collide.
        assertThat(Slugs.sourceName("https://example.com/politics.xml"))
                .isNotEqualTo(Slugs.sourceName("https://example.com/sport.xml"));
    }

    @Test
    void sourceName_forNonLatinHost_fallsBackToTheHash() {
        // Slugging a Cyrillic host yields nothing, so the hash has to carry
        // the whole name. The human-facing label is `title`, not this.
        String name = Slugs.sourceName("https://пример.рф/feed");
        assertThat(name).isNotBlank().matches("^[a-z0-9-]+$");
    }

    @Test
    void slugify_foldsAccentsIntoBaseLetters() {
        assertThat(Slugs.slugify("Süddeutsche Zeitung")).isEqualTo("suddeutsche-zeitung");
        assertThat(Slugs.slugify("L'Équipe")).isEqualTo("l-equipe");
    }

    @Test
    void slugify_stripsLeadingAndTrailingSeparators() {
        assertThat(Slugs.slugify("  ...News!  ")).isEqualTo("news");
    }

    @Test
    void slugify_ofUnslugabbleText_isEmpty() {
        assertThat(Slugs.slugify("日本語")).isEmpty();
        assertThat(Slugs.slugify("")).isEmpty();
    }

    @Test
    void hostOf_returnsEmptyForGarbage() {
        assertThat(Slugs.hostOf("https://example.com/x")).isEqualTo("example.com");
        assertThat(Slugs.hostOf("not a url")).isEmpty();
    }
}
