package de.mhus.hrafnagud.munin.category;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How a raw category becomes a key.
 *
 * <p>{@code Personal finance}, {@code personal-finance} and
 * {@code Personal  Finance} are one entry in the mapping table rather than
 * three — a publisher's punctuation is not a distinction worth resolving twice.
 * The same normalisation is applied to the vocabulary's labels when the bundled
 * table is generated, which is what lets the two be compared at all — see
 * {@code scripts/generate-mediatopics-tsv.py}, which must be kept in step with
 * {@link #normalise(String)}. If the two drift apart, nothing fails: the
 * matcher just quietly stops resolving things.
 *
 * <p>Lossy on purpose, which is why the raw string is stored beside the key:
 * somebody looking at a questionable mapping needs to see what the publisher
 * actually wrote.
 */
public final class CategoryKeys {

    /**
     * Words dropped before comparing token sets. Deliberately short and
     * multilingual — these are the joiners that differ between a publisher's
     * phrasing and a vocabulary label ("economy and finance" against "economy,
     * business and finance"), not a stop-word list.
     */
    private static final Set<String> JOINERS =
            Set.of("and", "the", "of", "und", "et", "der", "die");

    private CategoryKeys() {
        /* utility */
    }

    /**
     * Lowercased, accents folded, punctuation to single spaces.
     *
     * <p><b>Latin accents are folded; other scripts are kept.</b> The first
     * version folded to ASCII, which reads as the same thing and is not: it
     * turned {@code Économie} into {@code economie} and {@code Політика},
     * {@code اقتصاد} and {@code 经济} all into the empty string. The damage was
     * on both sides — 126 categories in the archive could never be mapped, and
     * the vocabulary's own Arabic and Chinese labels, a quarter of the ten
     * thousand, were dropped when the bundled table was generated. A collector
     * of world news cannot have a key function that only survives Western
     * Europe.
     *
     * <p>{@code \\p{M}} after NFKD is what does the accent folding, and it is
     * script-neutral: it removes the combining acute from a decomposed
     * {@code é} and leaves a Cyrillic or Arabic letter alone. Only the final
     * character class had to change, from {@code [^a-z0-9]} to letters and
     * digits in any script.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD);
        String folded = decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * Comparable tokens: joiners dropped, crude singulars.
     *
     * <p>The singular rule is deliberately naive. It exists for one measured
     * class of near-miss — {@code Sports} against the label {@code sport} — and
     * a real stemmer would introduce its own errors across thirteen languages
     * for a gain nobody has measured.
     */
    public static Set<String> tokens(String raw) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalise(raw).split(" ")) {
            if (token.isBlank() || JOINERS.contains(token)) {
                continue;
            }
            tokens.add(singular(token));
        }
        return tokens;
    }

    private static String singular(String word) {
        if (word.length() <= 4) {
            return word;
        }
        if (word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("es") || word.endsWith("s")) {
            return word.endsWith("es") && word.length() > 5
                    ? word.substring(0, word.length() - 2)
                    : word.substring(0, word.length() - 1);
        }
        return word;
    }
}
