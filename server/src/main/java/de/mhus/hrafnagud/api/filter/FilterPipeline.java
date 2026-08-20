package de.mhus.hrafnagud.api.filter;

/**
 * Which expensive step a rule set governs.
 *
 * <p>Two, because they are separately expensive: a body fetch costs a request
 * and a translation costs tokens. A paywalled foreign source can be worth
 * translating for its teaser while its body is not worth fetching, and a
 * source that already writes in the pivot language needs no translation while
 * its text is still wanted. One rule engine, two answers — see
 * specs/filter.md §5.
 */
public enum FilterPipeline {

    /** Fetching the article page. */
    CONTENT,

    /** Translating title and teaser. */
    TRANSLATION
}
