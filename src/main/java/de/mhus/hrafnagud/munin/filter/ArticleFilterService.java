package de.mhus.hrafnagud.munin.filter;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterOutcome;
import de.mhus.hrafnagud.api.filter.FilterOutcomes;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterRuleType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Applies the rules to one article.
 *
 * <p>Pure with respect to the archive: it reads the in-memory rule set and the
 * facts it is handed, touches no collection, and is called once per ingested
 * article. The whole cost is a few string comparisons per rule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleFilterService {

    private final FilterRuleRegistry registry;

    /** Both pipelines at once, which is what ingest needs. */
    public FilterOutcomes evaluate(FilterSubject subject) {
        return new FilterOutcomes(evaluate(FilterPipeline.CONTENT, subject),
                evaluate(FilterPipeline.TRANSLATION, subject));
    }

    /**
     * Accept, then deny, then the default.
     *
     * <p>Accept first is what makes the useful shape expressible: a broad
     * exclusion with narrow exceptions — deny a whole region, except what is
     * about sport. And because each step is "does any rule match", the rules
     * are a set: no ordering, no priority, nothing about one rule depending on
     * which others exist.
     *
     * <p>The default is accept, which has a consequence worth remembering when
     * reading a rule set: an accept rule only ever does something when some
     * deny rule would otherwise have matched.
     */
    public FilterOutcome evaluate(FilterPipeline pipeline, FilterSubject subject) {
        FilterRuleRegistry.Snapshot rules = registry.snapshot();

        CompiledRule accepted = firstMatch(rules.accepts(pipeline), subject);
        if (accepted != null) {
            return FilterOutcome.of(FilterDecision.ACCEPT, accepted.name());
        }
        CompiledRule denied = firstMatch(rules.denies(pipeline), subject);
        if (denied != null) {
            return FilterOutcome.of(FilterDecision.DENY, denied.name());
        }
        return FilterOutcome.defaultAccept();
    }

    private @Nullable CompiledRule firstMatch(List<CompiledRule> rules, FilterSubject subject) {
        for (CompiledRule rule : rules) {
            for (String candidate : valuesFor(rule.type(), subject)) {
                if (rule.matches(candidate)) {
                    return rule;
                }
            }
        }
        return null;
    }

    /**
     * What one rule type reads off the article.
     *
     * <p>A multi-valued type matches when any element does. For {@code REGION}
     * and {@code TOPIC} the list is the <b>materialised ancestor path</b>, which
     * is what turns containment into an equality match: a rule naming Asia
     * matches a Singaporean source, and one naming sport matches an article
     * tagged Cricket, with no tree walk and no second query.
     */
    private static List<String> valuesFor(FilterRuleType type, FilterSubject subject) {
        return switch (type) {
            case URL -> List.of(subject.url());
            case HOST -> List.of(subject.host());
            case SOURCE -> subject.sourceNames();
            case LANGUAGE -> subject.language() == null ? List.of() : List.of(subject.language());
            case REGION -> subject.originPlaceIds();
            case CATEGORY -> subject.categories();
            case TOPIC -> subject.topicIds();
            case PROFILE -> subject.profile() == null ? List.of() : List.of(subject.profile());
        };
    }
}
