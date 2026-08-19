package de.mhus.hrafnagud.api.article;

/**
 * Where an article's language value came from.
 *
 * <p>Stored next to the language itself rather than folded into it, because
 * the three provenances are not equally trustworthy and a consumer needs to
 * be able to tell them apart. A feed's {@code <language>} element is
 * frequently absent, copy-pasted from a template, or simply wrong (a
 * Spanish-language feed declaring {@code en-US} is common), so a downstream
 * filter on "German articles" wants to know whether it is trusting a
 * publisher's claim or our own classifier.
 *
 * <ul>
 *   <li>{@link #SOURCE} — configured on the source by a human. Highest
 *       authority; never overridden by detection.</li>
 *   <li>{@link #FEED} — declared by the feed or the entry itself.</li>
 *   <li>{@link #DETECTED} — statistically classified from title and teaser.</li>
 *   <li>{@link #UNKNOWN} — no declaration and too little text to classify.
 *       The language field is {@code null} in this case.</li>
 * </ul>
 */
public enum LanguageSource {

    /** Configured on the source by a human. */
    SOURCE,

    /** Declared by the feed or entry. */
    FEED,

    /** Classified from the text. */
    DETECTED,

    /** Not determined. */
    UNKNOWN
}
