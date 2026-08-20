package de.mhus.hrafnagud.munin.filter;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterMatchType;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterRuleType;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * A rule ready to be applied: value folded once, pattern compiled once.
 *
 * <p>The compilation is the point. A rule is evaluated for every article of
 * every feed, and {@link Pattern#compile} per article per rule would be the
 * most expensive thing in the ingest path by a wide margin. It also moves the
 * failure: an invalid pattern cannot be compiled here, so it never becomes a
 * rule at all ({@link #compile}).
 *
 * @param pattern only set for {@link FilterMatchType#REGEX}
 */
record CompiledRule(
        String name,
        FilterPipeline pipeline,
        FilterDecision decision,
        FilterRuleType type,
        FilterMatchType matchType,
        String value,
        @Nullable Pattern pattern) {

    /**
     * @throws PatternSyntaxException if the rule is a regex and does not compile
     */
    static CompiledRule of(FilterRuleDocument rule) {
        String folded = rule.getValue().trim().toLowerCase(Locale.ROOT);
        Pattern pattern = rule.getMatchType() == FilterMatchType.REGEX
                // Folding the value is not enough for a regex: the pattern's
                // own literals are the operator's, and CASE_INSENSITIVE is
                // what keeps every match type behaving the same way.
                ? Pattern.compile(rule.getValue().trim(), Pattern.CASE_INSENSITIVE)
                : null;
        return new CompiledRule(rule.getName(), rule.getPipeline(), rule.getDecision(),
                rule.getType(), rule.getMatchType(), folded, pattern);
    }

    /** Whether one value from the article satisfies this rule. */
    boolean matches(String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        String folded = candidate.toLowerCase(Locale.ROOT);
        return switch (matchType) {
            case EXACT -> folded.equals(value);
            case PREFIX -> folded.startsWith(value);
            case SUFFIX -> folded.endsWith(value);
            case CONTAINS -> folded.contains(value);
            // find(), not matches(): an anchored-by-default regex would make
            // every useful pattern start with .* and end with .*, and the
            // operator who forgets gets silence rather than an error.
            case REGEX -> pattern != null && pattern.matcher(candidate).find();
        };
    }
}
