package de.mhus.hrafnagud.api.filter;

/**
 * How a rule's value is compared to what the article carries.
 *
 * <p>All comparisons are case-insensitive. A publisher's capitalisation is not
 * a distinction worth a second rule, and the alternative — every operator
 * writing {@code (?i)} in front of every pattern — is a footgun disguised as
 * precision.
 */
public enum FilterMatchType {

    EXACT,

    PREFIX,

    /** Ends with. The right one for domains, which nest from the right. */
    SUFFIX,

    CONTAINS,

    /**
     * Full regular expression.
     *
     * <p>Compiled when the rule is <b>saved</b>, so a typo is rejected with an
     * error the operator sees. Compiling at evaluation time would turn the same
     * typo into a rule that silently matches nothing, once per article.
     */
    REGEX
}
