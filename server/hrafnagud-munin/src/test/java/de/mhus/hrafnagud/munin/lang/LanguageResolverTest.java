package de.mhus.hrafnagud.munin.lang;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.article.LanguageSource;
import org.junit.jupiter.api.Test;

/**
 * Precedence over a claim, a declaration and a guess. The ordering encodes
 * how much each can be trusted, and the provenance travels with the value so
 * a consumer can tell which one it got.
 */
class LanguageResolverTest {

    /** Always claims Portuguese, so it is obvious when detection was used. */
    private final LanguageResolver resolver = new LanguageResolver(text -> "pt");

    private final LanguageResolver silentResolver = new LanguageResolver(text -> null);

    private static final String TEXT =
            "Der Stadtrat hat am Dienstag den lange verzögerten Verkehrsplan beschlossen.";

    @Test
    void sourceOverride_winsOverEverything() {
        LanguageResolver.Resolution result = resolver.resolve("de", "en", TEXT);

        assertThat(result.language()).isEqualTo("de");
        assertThat(result.source()).isEqualTo(LanguageSource.SOURCE);
    }

    @Test
    void feedDeclaration_winsOverDetection() {
        // Detection is not allowed to second-guess the publisher per entry:
        // a multilingual site quoting at length would otherwise make the
        // field unstable in a way no consumer can reason about.
        LanguageResolver.Resolution result = resolver.resolve(null, "en", TEXT);

        assertThat(result.language()).isEqualTo("en");
        assertThat(result.source()).isEqualTo(LanguageSource.FEED);
    }

    @Test
    void detection_isUsedWhenNothingWasDeclared() {
        LanguageResolver.Resolution result = resolver.resolve(null, null, TEXT);

        assertThat(result.language()).isEqualTo("pt");
        assertThat(result.source()).isEqualTo(LanguageSource.DETECTED);
    }

    @Test
    void regionalVariants_areReducedToThePrimarySubtag() {
        assertThat(resolver.resolve(null, "de-AT", TEXT).language()).isEqualTo("de");
        assertThat(resolver.resolve("pt_BR", null, TEXT).language()).isEqualTo("pt");
    }

    @Test
    void nonsenseDeclarations_fallThroughToDetection() {
        // "german" is not a language tag; treating it as one would store a
        // value nothing downstream can filter on.
        LanguageResolver.Resolution result = resolver.resolve(null, "german", TEXT);

        assertThat(result.language()).isEqualTo("pt");
        assertThat(result.source()).isEqualTo(LanguageSource.DETECTED);
    }

    @Test
    void whenTheClassifierAbstains_theResultIsHonestlyUnknown() {
        LanguageResolver.Resolution result = silentResolver.resolve(null, null, TEXT);

        assertThat(result.language()).isNull();
        assertThat(result.source()).isEqualTo(LanguageSource.UNKNOWN);
    }

    @Test
    void blankText_isNotHandedToTheClassifier() {
        LanguageResolver.Resolution result = resolver.resolve(null, null, "   ");

        assertThat(result.language()).isNull();
        assertThat(result.source()).isEqualTo(LanguageSource.UNKNOWN);
    }

    @Test
    void unknownResolution_neverCarriesALanguage() {
        assertThat(silentResolver.resolve(null, null, "").language()).isNull();
    }
}
