package de.mhus.hrafnagud.api.filter;

/**
 * What a rule says, and what was concluded about an article.
 *
 * <p>Deliberately one enum for both. A rule saying {@code DENY} and an article
 * being denied are the same statement about the same thing, and a second enum
 * with past-tense names would have to be mapped back and forth for no gain.
 */
public enum FilterDecision {

    /** Spend the request or the tokens. */
    ACCEPT,

    /** Do not. */
    DENY
}
