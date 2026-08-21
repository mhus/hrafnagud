package de.mhus.hrafnagud.api.article;

/**
 * State of an article's translation into the pivot language.
 *
 * <p>One language, not a set: translation here is the normalisation step
 * that lets every later stage — keywords, clustering, embeddings,
 * significance — work in a single language. Rendering an archive in
 * several display languages is a different job, and modelling both as
 * one would make neither clear.
 *
 * <ul>
 *   <li>{@link #PENDING} — queued, the initial state of anything not
 *       already in the pivot language.</li>
 *   <li>{@link #DONE} — a translation enrichment exists.</li>
 *   <li>{@link #SKIPPED} — the article already is in the pivot language,
 *       or an operator excluded it. Terminal and not a failure.</li>
 *   <li>{@link #FAILED} — retry budget exhausted.</li>
 * </ul>
 */
public enum TranslationStatus {

    PENDING,
    DONE,
    SKIPPED,
    FAILED;

    /** {@code true} when no further automatic attempt will be made. */
    public boolean terminal() {
        return this != PENDING;
    }
}
