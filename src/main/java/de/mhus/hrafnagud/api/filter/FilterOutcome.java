package de.mhus.hrafnagud.api.filter;

import org.jspecify.annotations.Nullable;

/**
 * What the rules concluded about one article for one pipeline.
 *
 * <p>The rule name is half the value of the whole mechanism. A filter whose
 * decisions cannot be explained is a filter nobody can fix, and "why is this
 * article not translated" has to be answerable from the article itself rather
 * than by re-deriving the answer and hoping the rules have not changed since.
 *
 * @param rule the rule that decided, or null when nothing matched and the
 *             default applied
 */
public record FilterOutcome(FilterDecision decision, @Nullable String rule) {

    private static final FilterOutcome DEFAULT_ACCEPT =
            new FilterOutcome(FilterDecision.ACCEPT, null);

    /** Accepted because no rule said otherwise. */
    public static FilterOutcome defaultAccept() {
        return DEFAULT_ACCEPT;
    }

    public static FilterOutcome of(FilterDecision decision, @Nullable String rule) {
        return new FilterOutcome(decision, rule);
    }

    public boolean denied() {
        return decision == FilterDecision.DENY;
    }
}
