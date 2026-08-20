package de.mhus.hrafnagud.munin.category;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stage one: match a category against the vocabulary's own labels. No model, no
 * network, cheap enough to run the moment a category is first seen.
 *
 * <p><b>What it is worth, measured</b> against 7,365 real categories in 44,542
 * uses:
 *
 * <table>
 *   <tr><th>rule</th><th>categories</th><th>uses</th></tr>
 *   <tr><td>exact label, all 13 languages</td><td>349 (4.7 %)</td><td>8,401 (18.9 %)</td></tr>
 *   <tr><td>plus token-set and singular equality</td><td>365 (5.0 %)</td><td>9,049 (20.3 %)</td></tr>
 *   <tr><td>plus single-word subset</td><td>528 (7.2 %)</td><td>10,356 (23.2 %)</td></tr>
 * </table>
 *
 * <p>So this is worth having and nowhere near sufficient: it clears the
 * frequent, unambiguous head — Cryptocurrency, Cricket, Chess, Tennis, and
 * with token equality Sports — and leaves the near-misses to a model.
 *
 * <p>The third rule is the interesting one. It reaches a fifth of all uses by
 * mapping any one-word category to any label containing that word, which is
 * also how {@code standard} becomes a topic. Its results are therefore
 * {@link de.mhus.hrafnagud.api.category.CategoryMappingStatus#GUESSED} — kept,
 * shown, and still queued for stage two — rather than acted on.
 *
 * <p><b>These figures replace higher ones</b> that were measured before the
 * rules were finished, and the correction is recorded in specs/categories.md §4
 * because two of the three causes were faults in the measurement: an empty key
 * in the label index made every non-Latin category look like an exact match,
 * and a single token was scored as confidently as several. The rules here are
 * the ones the numbers were taken from.
 */
@Component
@RequiredArgsConstructor
public class CategoryMatcher {

    private final TopicRegistry topics;

    /** Token set of a label → topic, for the second rule. */
    private final Map<Set<String>, String> byTokens = new HashMap<>();

    /** Single word → topic, for the third. Ambiguous words are dropped. */
    private final Map<String, String> byWord = new HashMap<>();

    @PostConstruct
    void index() {
        Map<String, Integer> wordCounts = new HashMap<>();

        // Sorted, and shallowest wins on a tie. Both matter: the label index is
        // a HashMap, so without an order the winner of a collision changed
        // between runs — and when two concepts share a token set, the broader
        // one is the better guess, because a publisher's section name is
        // broad. Without this, "Sports" resolved to eSports.
        List<Topic> ordered = new ArrayList<>(topics.all());
        ordered.sort(Comparator.comparingInt((Topic t) -> t.path().size())
                .thenComparing(Topic::id));

        for (Topic topic : ordered) {
            for (String label : topic.labels()) {
                Set<String> tokens = CategoryKeys.tokens(label);
                if (!tokens.isEmpty()) {
                    byTokens.putIfAbsent(tokens, topic.id());
                }
                for (String word : tokens) {
                    wordCounts.merge(word, 1, Integer::sum);
                    byWord.putIfAbsent(word, topic.id());
                }
            }
        }
        // A word appearing in labels of several concepts says nothing —
        // "development" is in a dozen. Keeping it would make the weakest rule
        // also the most confident-looking.
        wordCounts.forEach((word, count) -> {
            if (count > 1) {
                byWord.remove(word);
            }
        });
    }

    /** What stage one makes of one raw category. */
    public Optional<Match> match(String raw) {
        String key = CategoryKeys.normalise(raw);
        if (key.isEmpty()) {
            return Optional.empty();
        }

        Optional<Topic> exact = topics.byLabel(key);
        if (exact.isPresent()) {
            return Optional.of(new Match(exact.get(), MatchRule.LABEL_EXACT, 1.0));
        }

        Set<String> tokens = CategoryKeys.tokens(raw);
        String byTokenSet = byTokens.get(tokens);
        if (byTokenSet != null) {
            return topics.find(byTokenSet).map(topic -> new Match(topic,
                    MatchRule.LABEL_TOKENS, tokenConfidence(tokens, topic)));
        }

        if (tokens.size() == 1) {
            String word = tokens.iterator().next();
            String candidate = byWord.get(word);
            if (candidate != null) {
                // Deliberately below the threshold that would be acted on.
                return topics.find(candidate)
                        .map(topic -> new Match(topic, MatchRule.LABEL_WORD, 0.4));
            }
        }
        return Optional.empty();
    }

    /**
     * How much a token-set match is worth.
     *
     * <p>Two or more tokens agreeing is strong. <b>One token against a
     * one-word label is not</b>, and pretending otherwise was a mistake: it is
     * the same evidence as the single-word rule. {@code Sports} against
     * {@code sport} is right and {@code standard} against
     * {@code scientific standards} is wrong, and nothing about the strings
     * separates them.
     *
     * <p>What does separate them is depth, and only at the very top. A
     * publisher names broad sections, so a single word landing on one of the
     * seventeen <b>roots</b> is very likely meant: {@code sport} is a root.
     * One level down is already too permissive — {@code scientific standards}
     * hangs directly under science and technology, so a rule of "root or its
     * children" would have accepted {@code standard} as a topic. Anything but
     * a root is a guess and is scored as one.
     */
    private static double tokenConfidence(Set<String> tokens, Topic topic) {
        if (tokens.size() > 1) {
            return 0.9;
        }
        return topic.path().size() == 1 ? 0.9 : 0.4;
    }

    /** Which rule fired, recorded on the mapping so a bad rule can be found. */
    public enum MatchRule { LABEL_EXACT, LABEL_TOKENS, LABEL_WORD }

    /**
     * @param confidence 1.0 for an exact label, 0.9 for a token set, 0.4 for a
     *                   single word — the last being below what the service
     *                   treats as resolved.
     */
    public record Match(Topic topic, MatchRule rule, double confidence) {

        public @Nullable String topicId() {
            return topic.id();
        }
    }
}
