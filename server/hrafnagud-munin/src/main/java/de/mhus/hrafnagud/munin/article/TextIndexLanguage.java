package de.mhus.hrafnagud.munin.article;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Maps an article's language onto the stemmer MongoDB's text index will
 * accept for it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A MongoDB text index carries a <em>language override</em>: a field name
 * whose value selects the stemmer and stop-word list per document. It
 * defaults to a field literally called {@code language} — and an article has
 * one, holding a BCP-47 primary subtag.
 *
 * <p>MongoDB supports fifteen stemmers. A document whose override field holds
 * anything else is <b>rejected on write</b>:
 *
 * <pre>
 *   language override unsupported: ja
 * </pre>
 *
 * <p>In a worldwide news collector that is not a degradation, it is a wall.
 * Japanese, Chinese, Korean, Polish, Czech, Arabic, Ukrainian and Greek
 * articles could not be stored at all, and because the ingest loop has no
 * per-article catch, one such entry aborted the whole poll of its feed —
 * every tick, indefinitely. It went unnoticed because a German and English
 * archive is exactly the case that works.
 *
 * <p>So the article's own {@code language} is left alone — it is the honest
 * record of what the article is in, and it is part of the API — and a
 * separate {@code textLanguage} field carries the value MongoDB is allowed to
 * see.
 *
 * <h2>Why {@code none} rather than a default stemmer</h2>
 *
 * <p>{@code none} means: index the tokens, stem nothing, strip no stop words.
 * For a language MongoDB cannot stem that is the only truthful answer —
 * pretending Japanese is English would apply English stop words to it, which
 * is worse than doing nothing. It is also what an unknown language gets:
 * guessing a stemmer for text we could not classify would be a guess layered
 * on a guess.
 */
public final class TextIndexLanguage {

    /** MongoDB's answer for "index it, but do not pretend to understand it". */
    public static final String NONE = "none";

    /**
     * The stemmers MongoDB ships, keyed by the ISO 639-1 code an article
     * carries. Values are the long names rather than the codes: both are
     * accepted, and the long form makes a stored document self-explanatory.
     *
     * <p>Norwegian maps from both {@code no} (macrolanguage, what feeds
     * usually send) and {@code nb} (Bokmål, which MongoDB names).
     */
    private static final Map<String, String> STEMMERS = Map.ofEntries(
            Map.entry("da", "danish"),
            Map.entry("nl", "dutch"),
            Map.entry("en", "english"),
            Map.entry("fi", "finnish"),
            Map.entry("fr", "french"),
            Map.entry("de", "german"),
            Map.entry("hu", "hungarian"),
            Map.entry("it", "italian"),
            Map.entry("nb", "norwegian"),
            Map.entry("no", "norwegian"),
            Map.entry("pt", "portuguese"),
            Map.entry("ro", "romanian"),
            Map.entry("ru", "russian"),
            Map.entry("es", "spanish"),
            Map.entry("sv", "swedish"),
            Map.entry("tr", "turkish"));

    /** The long names, so a value already in that form passes through. */
    private static final Set<String> NAMES = Set.copyOf(STEMMERS.values());

    private TextIndexLanguage() {}

    /**
     * The value to store in the text-index override field.
     *
     * @param language BCP-47 primary subtag, a full tag like {@code de-AT},
     *                 a stemmer name, or {@code null}
     * @return a stemmer MongoDB accepts, or {@link #NONE}. Never {@code null}
     *         and never a value that would fail a write.
     */
    public static String of(@Nullable String language) {
        if (language == null || language.isBlank()) {
            return NONE;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        // A region subtag does not change the stemmer: de-AT stems as German.
        int region = normalized.indexOf('-');
        if (region > 0) {
            normalized = normalized.substring(0, region);
        }
        if (NAMES.contains(normalized)) {
            return normalized;
        }
        return STEMMERS.getOrDefault(normalized, NONE);
    }
}
