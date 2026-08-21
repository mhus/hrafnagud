package de.mhus.hrafnagud.munin.filter;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The enabled rules, compiled and held in memory.
 *
 * <p>Rules are tens and articles are millions, so evaluating one article must
 * not be a database read. The set is loaded at startup and replaced whenever a
 * rule is written — there is no refresh interval to wait out, because the
 * operator who just saved a rule is about to look at whether it worked.
 *
 * <p>The swap is atomic in the sense that matters: a fully built snapshot is
 * published to a volatile field in one assignment, so a concurrent ingest sees
 * either every rule of the old set or every rule of the new one, never a
 * half-loaded mixture. Same shape as the model catalogue's snapshot swap.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FilterRuleRegistry {

    private final FilterRuleRepository repository;

    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * Accept and deny kept apart per pipeline, because that is exactly how they
     * are asked for: all accepts first, then all denies (specs/filter.md §2.1).
     * Splitting once at load beats filtering per article.
     */
    record Snapshot(Map<FilterPipeline, List<CompiledRule>> accepts,
                    Map<FilterPipeline, List<CompiledRule>> denies,
                    int size) {

        static Snapshot empty() {
            return new Snapshot(new EnumMap<>(FilterPipeline.class),
                    new EnumMap<>(FilterPipeline.class), 0);
        }

        List<CompiledRule> accepts(FilterPipeline pipeline) {
            return accepts.getOrDefault(pipeline, List.of());
        }

        List<CompiledRule> denies(FilterPipeline pipeline) {
            return denies.getOrDefault(pipeline, List.of());
        }
    }

    @PostConstruct
    public void reload() {
        Map<FilterPipeline, List<CompiledRule>> accepts = new EnumMap<>(FilterPipeline.class);
        Map<FilterPipeline, List<CompiledRule>> denies = new EnumMap<>(FilterPipeline.class);
        int loaded = 0;

        for (FilterRuleDocument rule : repository.findByEnabledTrue()) {
            CompiledRule compiled;
            try {
                compiled = CompiledRule.of(rule);
            } catch (RuntimeException e) {
                // Only reachable for a rule that was written before validation
                // existed, or edited in the database by hand. Skipping one bad
                // rule is right: refusing to start would take the whole
                // collector down over a typo in one deny rule, and refusing to
                // load the rest would silently widen what gets spent on.
                log.warn("Filter rule '{}' does not compile and is ignored: {}",
                        rule.getName(), e.getMessage());
                continue;
            }
            Map<FilterPipeline, List<CompiledRule>> target =
                    compiled.decision() == FilterDecision.ACCEPT ? accepts : denies;
            target.computeIfAbsent(compiled.pipeline(), key -> new ArrayList<>()).add(compiled);
            loaded++;
        }

        snapshot = new Snapshot(accepts, denies, loaded);
        log.info("Filter rules loaded: {} enabled", loaded);
    }

    Snapshot snapshot() {
        return snapshot;
    }

    /** How many enabled rules are in effect. */
    public int size() {
        return snapshot.size();
    }
}
