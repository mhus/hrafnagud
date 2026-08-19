package de.mhus.hrafnagud.translate;

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
 * <p>One text per call. A provider that could translate a title and a
 * teaser in a single request would save the prompt overhead, but only by
 * requiring structured output and the error handling that comes with it —
 * worth doing when the cost is measured, not before.
 */
public interface TranslationProvider {

    /** Identifier stored with each translation, e.g. {@code vance-ode}. */
    String name();

    /**
     * Translates {@code text} into {@code targetLanguage}.
     *
     * @param targetLanguage BCP-47 primary subtag ({@code de}, {@code en})
     * @return the translation; never {@code null}, never blank for
     *         non-blank input
     * @throws TranslationException when the translation could not be
     *         produced — the caller decides whether to retry from
     *         {@link TranslationException#isRetryable()}
     */
    String translate(String text, String targetLanguage);
}
