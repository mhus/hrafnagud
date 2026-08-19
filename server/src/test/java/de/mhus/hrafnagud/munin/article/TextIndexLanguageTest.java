package de.mhus.hrafnagud.munin.article;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mapping that keeps MongoDB from rejecting an article.
 *
 * <p>Worth its own test class because the failure it prevents is not a
 * degradation: a language MongoDB does not know makes the write fail, and
 * the ingest loop has no per-article catch, so one such entry aborted the
 * whole poll of its feed. The cases below are the ones a worldwide collector
 * actually meets.
 */
class TextIndexLanguageTest {

    @ParameterizedTest
    @CsvSource({
            "de, german",
            "en, english",
            "fr, french",
            "ru, russian",
            "tr, turkish",
    })
    void a_supported_language_maps_to_its_stemmer(String language, String expected) {
        assertThat(TextIndexLanguage.of(language)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja", "zh", "ko", "th", "pl", "cs", "ar", "uk", "el", "he"})
    void a_language_mongodb_cannot_stem_maps_to_none(String language) {
        // Not a fallback to English: applying English stop words to Japanese
        // is worse than doing nothing, and `none` still indexes the tokens.
        assertThat(TextIndexLanguage.of(language)).isEqualTo(TextIndexLanguage.NONE);
    }

    @ParameterizedTest
    @CsvSource({"no, norwegian", "nb, norwegian"})
    void both_norwegian_codes_map(String language, String expected) {
        // Feeds send the macrolanguage `no`; MongoDB names Bokmål.
        assertThat(TextIndexLanguage.of(language)).isEqualTo(expected);
    }

    @Test
    void a_region_subtag_does_not_change_the_stemmer() {
        assertThat(TextIndexLanguage.of("de-AT")).isEqualTo("german");
        assertThat(TextIndexLanguage.of("pt-BR")).isEqualTo("portuguese");
        assertThat(TextIndexLanguage.of("zh-Hant")).isEqualTo(TextIndexLanguage.NONE);
    }

    @Test
    void case_and_whitespace_do_not_decide_whether_an_article_can_be_stored() {
        assertThat(TextIndexLanguage.of(" DE ")).isEqualTo("german");
        assertThat(TextIndexLanguage.of("EN")).isEqualTo("english");
    }

    @Test
    void a_stemmer_name_passes_through() {
        // The stored value is the long form, so re-deriving it from an
        // existing document must not turn german into none.
        assertThat(TextIndexLanguage.of("german")).isEqualTo("german");
        assertThat(TextIndexLanguage.of("none")).isEqualTo(TextIndexLanguage.NONE);
    }

    @Test
    void an_unknown_language_never_yields_a_value_that_would_fail_a_write() {
        assertThat(TextIndexLanguage.of(null)).isEqualTo(TextIndexLanguage.NONE);
        assertThat(TextIndexLanguage.of("")).isEqualTo(TextIndexLanguage.NONE);
        assertThat(TextIndexLanguage.of("   ")).isEqualTo(TextIndexLanguage.NONE);
        assertThat(TextIndexLanguage.of("not-a-language")).isEqualTo(TextIndexLanguage.NONE);
    }
}
