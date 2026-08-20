package de.mhus.hrafnagud.munin.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterMatchType;
import de.mhus.hrafnagud.api.filter.FilterOutcome;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterRuleType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The evaluation order, and the properties that follow from it.
 *
 * <p>Driven through the real {@link FilterRuleRegistry} against a stubbed
 * repository rather than a hand-built rule set: the split into accept and deny
 * lists happens at load, so a test that bypassed it would not be testing the
 * thing that decides.
 */
class ArticleFilterServiceTest {

    private final List<FilterRuleDocument> stored = new ArrayList<>();
    private final FilterRuleRepository repository = mock(FilterRuleRepository.class);

    private ArticleFilterService service() {
        when(repository.findByEnabledTrue()).thenReturn(List.copyOf(stored));
        FilterRuleRegistry registry = new FilterRuleRegistry(repository);
        registry.reload();
        return new ArticleFilterService(registry);
    }

    private void rule(FilterDecision decision, FilterRuleType type, FilterMatchType match,
            String value) {
        stored.add(FilterRuleDocument.builder()
                .name(decision + "-" + type + "-" + value)
                .pipeline(FilterPipeline.TRANSLATION)
                .decision(decision)
                .type(type)
                .matchType(match)
                .value(value)
                .enabled(true)
                .build());
    }

    private static FilterSubject article(String url, String language, List<String> places,
            List<String> categories, List<String> topics) {
        return FilterSubject.of(url, List.of("some-feed"), language, places, categories, topics,
                "news");
    }

    private FilterOutcome decide(FilterSubject subject) {
        return service().evaluate(FilterPipeline.TRANSLATION, subject);
    }

    @Test
    void with_no_rules_everything_is_accepted() {
        FilterOutcome outcome = decide(article("https://example.com/a", "de", List.of(),
                List.of(), List.of()));

        assertThat(outcome.decision()).isEqualTo(FilterDecision.ACCEPT);
        assertThat(outcome.rule()).isNull();
    }

    @Test
    void a_deny_rule_names_itself_in_the_outcome() {
        rule(FilterDecision.DENY, FilterRuleType.HOST, FilterMatchType.SUFFIX, "youtube.com");

        FilterOutcome outcome = decide(article("https://www.youtube.com/watch?v=1", "en",
                List.of(), List.of(), List.of()));

        assertThat(outcome.denied()).isTrue();
        // The rule name is the whole point of recording a decision: without it
        // "why is this not translated" has no answer.
        assertThat(outcome.rule()).isEqualTo("DENY-HOST-youtube.com");
    }

    /** The order the whole design rests on: an accept is an exception to a deny. */
    @Test
    void an_accept_rule_beats_a_deny_rule() {
        rule(FilterDecision.DENY, FilterRuleType.REGION, FilterMatchType.EXACT, "m49:142");
        rule(FilterDecision.ACCEPT, FilterRuleType.TOPIC, FilterMatchType.EXACT,
                "medtop:15000000");

        FilterSubject asianSport = article("https://sg.example.com/a", "en",
                List.of("m49:001", "m49:142", "iso:SG"), List.of(),
                List.of("medtop:15000000", "medtop:20000822"));

        assertThat(decide(asianSport).decision()).isEqualTo(FilterDecision.ACCEPT);
        assertThat(decide(article("https://sg.example.com/b", "en",
                List.of("m49:001", "m49:142", "iso:SG"), List.of(), List.of())).denied())
                .isTrue();
    }

    /**
     * Containment for free, because the ancestor path is materialised on the
     * article. A rule naming Asia has to match a Singaporean source without
     * anybody walking a hierarchy.
     */
    @Test
    void a_region_rule_matches_through_the_ancestor_path() {
        rule(FilterDecision.DENY, FilterRuleType.REGION, FilterMatchType.EXACT, "m49:142");

        assertThat(decide(article("https://a.example.com/1", "en",
                List.of("m49:001", "m49:142", "iso:SG"), List.of(), List.of())).denied()).isTrue();
        assertThat(decide(article("https://b.example.com/1", "en",
                List.of("m49:001", "m49:150", "iso:DE"), List.of(), List.of())).denied())
                .isFalse();
    }

    /** Same mechanism one level up: sport catches cricket. */
    @Test
    void a_topic_rule_matches_through_the_ancestor_path() {
        rule(FilterDecision.DENY, FilterRuleType.TOPIC, FilterMatchType.EXACT, "medtop:15000000");

        assertThat(decide(article("https://a.example.com/1", "en", List.of(), List.of(),
                List.of("medtop:15000000", "medtop:20000822", "medtop:20000888"))).denied())
                .isTrue();
    }

    /**
     * The reason HOST exists as a type of its own: the same rule written as a
     * substring of the whole URL fires on a URL that only mentions the domain.
     */
    @Test
    void a_host_rule_does_not_fire_on_a_url_that_merely_mentions_the_domain() {
        rule(FilterDecision.DENY, FilterRuleType.HOST, FilterMatchType.SUFFIX, "youtube.com");

        assertThat(decide(article("https://news.example.com/p?ref=https://youtube.com/x", "en",
                List.of(), List.of(), List.of())).denied()).isFalse();
    }

    @Test
    void a_host_rule_ignores_the_www_prefix() {
        rule(FilterDecision.DENY, FilterRuleType.HOST, FilterMatchType.EXACT, "example.com");

        assertThat(decide(article("https://www.example.com/a", "en", List.of(), List.of(),
                List.of())).denied()).isTrue();
    }

    @Test
    void a_source_rule_matches_any_of_the_sources_an_article_arrived_through() {
        rule(FilterDecision.DENY, FilterRuleType.SOURCE, FilterMatchType.EXACT, "second-feed");

        FilterSubject deduplicated = FilterSubject.of("https://example.com/a",
                List.of("first-feed", "second-feed"), "en", List.of(), List.of(), List.of(),
                "news");

        assertThat(decide(deduplicated).denied()).isTrue();
    }

    @Test
    void a_category_rule_reads_the_publishers_own_words() {
        rule(FilterDecision.DENY, FilterRuleType.CATEGORY, FilterMatchType.CONTAINS, "sponsor");

        assertThat(decide(article("https://example.com/a", "en", List.of(),
                List.of("Sponsored Content"), List.of())).denied()).isTrue();
    }

    @Test
    void matching_is_case_insensitive_including_regex() {
        rule(FilterDecision.DENY, FilterRuleType.LANGUAGE, FilterMatchType.EXACT, "DE");
        rule(FilterDecision.DENY, FilterRuleType.URL, FilterMatchType.REGEX, "/AMP/");

        assertThat(decide(article("https://example.com/a", "de", List.of(), List.of(),
                List.of())).denied()).isTrue();
        assertThat(decide(article("https://example.com/amp/x", "en", List.of(), List.of(),
                List.of())).denied()).isTrue();
    }

    /** A rule for one pipeline must not decide the other. */
    @Test
    void rules_are_scoped_to_their_pipeline() {
        rule(FilterDecision.DENY, FilterRuleType.HOST, FilterMatchType.EXACT, "example.com");

        FilterSubject subject = article("https://example.com/a", "en", List.of(), List.of(),
                List.of());
        ArticleFilterService service = service();

        assertThat(service.evaluate(FilterPipeline.TRANSLATION, subject).denied()).isTrue();
        assertThat(service.evaluate(FilterPipeline.CONTENT, subject).denied()).isFalse();
    }

    /**
     * A rule whose type reads a value the article does not have cannot match.
     * The alternative — treating absent as empty and letting a {@code CONTAINS}
     * rule match it — would silently deny everything with no language.
     */
    @Test
    void a_rule_on_a_missing_value_does_not_match() {
        rule(FilterDecision.DENY, FilterRuleType.LANGUAGE, FilterMatchType.CONTAINS, "e");

        FilterSubject noLanguage = FilterSubject.of("https://example.com/a", List.of("feed"),
                null, List.of(), List.of(), List.of(), null);

        assertThat(decide(noLanguage).denied()).isFalse();
    }

    @Test
    void a_rule_that_does_not_compile_is_skipped_rather_than_fatal() {
        stored.add(FilterRuleDocument.builder()
                .name("broken")
                .pipeline(FilterPipeline.TRANSLATION)
                .decision(FilterDecision.DENY)
                .type(FilterRuleType.URL)
                .matchType(FilterMatchType.REGEX)
                .value("[unclosed")
                .enabled(true)
                .build());
        rule(FilterDecision.DENY, FilterRuleType.HOST, FilterMatchType.EXACT, "example.com");

        // The good rule still applies. Refusing to start over one bad pattern
        // would take the collector down; ignoring the rest would widen what
        // gets spent on.
        assertThat(decide(article("https://example.com/a", "en", List.of(), List.of(),
                List.of())).denied()).isTrue();
    }
}
