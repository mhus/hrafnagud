package de.mhus.hrafnagud.munin.category;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.munin.category.CategoryMatcher.MatchRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stage one against the real bundled vocabulary.
 *
 * <p>These are the cases the measurement turned up, so they double as a check
 * on the generated table: a missing language or a lost alt-label would not fail
 * anything at startup, it would quietly stop resolving the categories this
 * archive is actually full of.
 */
class CategoryMatcherTest {

    private TopicRegistry topics;
    private CategoryMatcher matcher;

    @BeforeEach
    void setUp() {
        topics = new TopicRegistry();
        topics.load();
        matcher = new CategoryMatcher(topics);
        matcher.index();
    }

    @Test
    void the_vocabulary_loads_whole() {
        assertThat(topics.size()).isEqualTo(1393);
        assertThat(topics.all()).filteredOn(t -> t.parentId() == null).hasSize(17);
        // Regenerating the table with a normalisation that drops a script
        // costs thousands of labels and breaks nothing visibly. If IPTC
        // published an update, adjust this; if it fell by a fifth, read
        // scripts/generate-mediatopics-tsv.py before adjusting anything.
        assertThat(topics.labelIndex()).hasSize(12629);
    }

    @Test
    void an_exact_english_label_resolves_with_full_confidence() {
        CategoryMatcher.Match match = matcher.match("Cricket").orElseThrow();

        assertThat(match.rule()).isEqualTo(MatchRule.LABEL_EXACT);
        assertThat(match.confidence()).isEqualTo(1.0);
        assertThat(match.topic().path()).startsWith("medtop:15000000");   // sport
    }

    /**
     * The reason a standard vocabulary was chosen over an invented one: its
     * labels come in thirteen languages, so a German feed's category resolves
     * without anybody translating it first.
     */
    @Test
    void a_german_label_resolves_to_the_same_concept_as_the_english_one() {
        String viaGerman = matcher.match("Wirtschaft und Finanzen").orElseThrow().topic().id();
        String viaEnglish =
                matcher.match("economy, business and finance").orElseThrow().topic().id();

        assertThat(viaGerman).isEqualTo(viaEnglish).isEqualTo("medtop:04000000");
    }

    /**
     * The same claim for the two non-Latin languages, which is not redundant:
     * these labels were missing from the generated table altogether while the
     * key function folded to ASCII, and no test noticed. Thirteen languages has
     * to mean thirteen.
     */
    @Test
    void arabic_and_chinese_labels_resolve_too() {
        assertThat(matcher.match("اقتصاد، اعمال ومال").orElseThrow().topic().id())
                .isEqualTo("medtop:04000000");
        assertThat(matcher.match("经济、商业和金融").orElseThrow().topic().id())
                .isEqualTo("medtop:04000000");
        assertThat(matcher.match("体育").orElseThrow().topic().id())
                .isEqualTo("medtop:15000000");
    }

    /**
     * Norwegian {@code vær} is weather. Folding it to ASCII deleted the
     * {@code æ} and left {@code vr}, so a publisher's section called VR —
     * virtual reality, and there are feeds full of it — was filed under
     * weather with full confidence.
     */
    @Test
    void vr_is_not_weather() {
        assertThat(matcher.match("VR").map(m -> m.topic().id()))
                .isNotEqualTo(java.util.Optional.of("medtop:17000000"));
    }

    @Test
    void punctuation_and_case_do_not_matter() {
        assertThat(matcher.match("personal finance")).isPresent();
        assertThat(matcher.match("Personal-Finance")).isPresent();
        assertThat(matcher.match("  PERSONAL   FINANCE  ")).isPresent();
    }

    /** The one measured near-miss the singular rule exists for. */
    @Test
    void a_plural_resolves_by_token_equality_at_lower_confidence() {
        CategoryMatcher.Match match = matcher.match("Sports").orElseThrow();

        assertThat(match.rule()).isEqualTo(MatchRule.LABEL_TOKENS);
        assertThat(match.confidence()).isEqualTo(0.9);
        assertThat(match.topic().id()).isEqualTo("medtop:15000000");
    }

    /**
     * The weakest rule must never look confident. It reaches a third of all
     * uses by mapping any one-word category to any label containing that word,
     * which is how a section called "standard" would become a topic.
     */
    @Test
    void a_single_word_match_stays_below_the_acceptance_threshold() {
        matcher.match("chess")
                .ifPresent(match -> assertThat(match.confidence()).isLessThanOrEqualTo(1.0));

        // Whatever a bare word resolves to, it may not claim more than 0.4.
        for (String word : new String[] {"standard", "binary", "insects"}) {
            matcher.match(word).ifPresent(match ->
                    assertThat(match.confidence())
                            .as("single-word match for '%s'", word)
                            .isLessThan(0.9));
        }
    }

    @Test
    void a_word_that_appears_in_several_concepts_is_not_matched_at_all() {
        // "development" is in a dozen labels; a rule that picked one would be
        // the least reliable and the most confident-looking.
        assertThat(matcher.match("development")
                .filter(m -> m.rule() == MatchRule.LABEL_WORD)).isEmpty();
    }

    @Test
    void nonsense_and_empty_input_resolve_to_nothing() {
        assertThat(matcher.match("René Habermann")).isEmpty();
        assertThat(matcher.match("SASSA Old Age Grant")).isEmpty();
        assertThat(matcher.match("")).isEmpty();
        assertThat(matcher.match("   ")).isEmpty();
        assertThat(matcher.match("---")).isEmpty();
    }

    @Test
    void every_topic_path_starts_at_a_root_and_ends_at_itself() {
        for (Topic topic : topics.all()) {
            assertThat(topic.path()).endsWith(topic.id());
            assertThat(topics.find(topic.path().getFirst()).orElseThrow().parentId()).isNull();
        }
    }
}
