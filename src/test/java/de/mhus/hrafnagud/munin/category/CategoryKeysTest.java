package de.mhus.hrafnagud.munin.category;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The key function, and in particular the one property it lost once already:
 * <b>it folds Latin accents without deleting other scripts</b>.
 *
 * <p>The first version folded to ASCII. Nothing failed — no test, no startup
 * check, no log line — it simply meant that a quarter of the vocabulary's
 * labels and every non-Latin category in the archive became the empty string.
 * A key function that silently maps distinct inputs to nothing is the kind of
 * bug that only shows up as a number being lower than expected, which is
 * exactly how this one was found. Hence these tests.
 */
class CategoryKeysTest {

    @Test
    void punctuation_and_case_collapse_to_one_key() {
        assertThat(CategoryKeys.normalise("Personal finance")).isEqualTo("personal finance");
        assertThat(CategoryKeys.normalise("personal-finance")).isEqualTo("personal finance");
        assertThat(CategoryKeys.normalise("  PERSONAL   FINANCE  "))
                .isEqualTo("personal finance");
        assertThat(CategoryKeys.normalise("Economy, business & finance"))
                .isEqualTo("economy business finance");
    }

    @Test
    void latin_accents_are_folded() {
        assertThat(CategoryKeys.normalise("Économie et finances"))
                .isEqualTo("economie et finances");
        assertThat(CategoryKeys.normalise("Politík")).isEqualTo("politik");
    }

    /** The regression. Each of these was previously the empty string. */
    @Test
    void other_scripts_survive() {
        assertThat(CategoryKeys.normalise("Політика")).isEqualTo("політика");
        assertThat(CategoryKeys.normalise("Россия")).isEqualTo("россия");
        assertThat(CategoryKeys.normalise("اقتصاد، اعمال ومال")).isEqualTo("اقتصاد اعمال ومال");
        assertThat(CategoryKeys.normalise("经济、商业和金融")).isEqualTo("经济 商业和金融");
        assertThat(CategoryKeys.normalise("Υγεία")).isEqualTo("υγεια");
    }

    /**
     * Letters with no ASCII decomposition were being deleted rather than
     * folded, which is subtler than losing a whole script and did more harm:
     * Norwegian {@code vær} (weather) became {@code vr}, so a category named VR
     * resolved to weather.
     */
    @Test
    void letters_without_an_ascii_equivalent_are_not_deleted() {
        assertThat(CategoryKeys.normalise("vær")).isEqualTo("vær");
        assertThat(CategoryKeys.normalise("Fußball")).isEqualTo("fußball");
        assertThat(CategoryKeys.normalise("Miljø")).isEqualTo("miljø");
    }

    @Test
    void a_string_with_no_letters_or_digits_has_no_key() {
        assertThat(CategoryKeys.normalise("---")).isEmpty();
        assertThat(CategoryKeys.normalise("   ")).isEmpty();
        assertThat(CategoryKeys.normalise(" » ")).isEmpty();
    }

    @Test
    void tokens_drop_joiners_and_crude_plurals() {
        assertThat(CategoryKeys.tokens("Economy and Finance"))
                .containsExactlyInAnyOrder("economy", "finance");
        assertThat(CategoryKeys.tokens("Sports")).containsExactly("sport");
    }

    /**
     * Single letters are not joiners, and treating them as such is how
     * {@code Sports} came to resolve to eSports: Portuguese {@code e sport}
     * lost its {@code e} and its token set became the same as {@code sport}'s.
     */
    @Test
    void a_single_letter_is_a_token_not_a_joiner() {
        assertThat(CategoryKeys.tokens("e sport")).containsExactlyInAnyOrder("e", "sport");
    }
}
