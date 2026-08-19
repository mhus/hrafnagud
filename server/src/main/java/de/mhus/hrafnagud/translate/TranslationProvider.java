package de.mhus.hrafnagud.translate;

import org.jspecify.annotations.Nullable;

/**
 * Whatever actually performs a translation.
 *
 * <p>An interface rather than a direct call into Vancetope, for the same
 * reason {@code SourceReader} and {@code SourceListParser} are interfaces:
 * the choice of engine is configuration, not architecture. A brain, a
 * model called directly, a machine-translation API — the queue that drives
 * this cannot tell them apart, and swapping one for another must not touch
 * anything but the bean that is wired.
 *
 * <p>Title and teaser go in <em>one</em> call. Measured against a paid
 * model at realistic volume, the recipe prompt dominates the token bill —
 * roughly five times the article text — so translating the two fields
 * separately doubles the cost of the expensive half to save nothing.
 */
public interface TranslationProvider {

    /** Identifier stored with each result, e.g. {@code vance-ode}. */
    String name();

    /**
     * Translates an article's title and teaser in one request.
     *
     * @param summary        may be {@code null} — many feeds carry none
     * @param targetLanguage BCP-47 primary subtag ({@code de}, {@code en})
     * @throws TranslationException when the translation could not be
     *         produced; the caller decides whether to retry from
     *         {@link TranslationException#isRetryable()}
     */
    TranslatedText translate(String title, @Nullable String summary, String targetLanguage);
}
