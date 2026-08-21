package de.mhus.hrafnagud.munin.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The filter decides how much of somebody else's directory this installation
 * pulls in, so its edges are worth pinning down: getting {@code *} versus
 * {@code **} wrong is the difference between 25 lists and 66.
 */
class CatalogEntryFilterTest {

    @Test
    void an_empty_filter_accepts_everything() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of(), List.of());

        assertThat(filter.accepts("countries/with_category/Germany.opml")).isTrue();
        assertThat(filter.accepts("anything")).isTrue();
    }

    @Test
    void a_single_star_does_not_cross_a_slash() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of("countries/*"), List.of());

        assertThat(filter.accepts("countries/Germany.opml")).isTrue();
        assertThat(filter.accepts("countries/with_category/Germany.opml")).isFalse();
    }

    @Test
    void a_double_star_crosses_slashes() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of("countries/**"), List.of());

        assertThat(filter.accepts("countries/with_category/Germany.opml")).isTrue();
        assertThat(filter.accepts("recommended/with_category/Tech.opml")).isFalse();
    }

    @Test
    void exclude_wins_over_include() {
        CatalogEntryFilter filter = new CatalogEntryFilter(
                List.of("**"), List.of("**/Memes.opml"));

        assertThat(filter.accepts("recommended/with_category/Tech.opml")).isTrue();
        assertThat(filter.accepts("recommended/with_category/Memes.opml")).isFalse();
    }

    /** Without an include list, an exclude is still a veto over everything else. */
    @Test
    void exclude_alone_removes_from_the_whole_set() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of(), List.of("**/Chess.opml"));

        assertThat(filter.accepts("recommended/with_category/Chess.opml")).isFalse();
        assertThat(filter.accepts("recommended/with_category/Science.opml")).isTrue();
    }

    @Test
    void matching_is_case_insensitive() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of("Countries/**"), List.of());

        assertThat(filter.accepts("countries/with_category/Germany.opml")).isTrue();
    }

    /**
     * A dot is a dot. In a regex it would match any character, which is how a
     * filter meant for one file quietly takes in its neighbours.
     */
    @Test
    void a_glob_is_not_a_regex() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of("a.opml"), List.of());

        assertThat(filter.accepts("a.opml")).isTrue();
        assertThat(filter.accepts("axopml")).isFalse();
    }

    @Test
    void a_question_mark_matches_one_character() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of("feed?.opml"), List.of());

        assertThat(filter.accepts("feed1.opml")).isTrue();
        assertThat(filter.accepts("feed12.opml")).isFalse();
    }

    @Test
    void blank_patterns_are_ignored_rather_than_matching_nothing() {
        CatalogEntryFilter filter = new CatalogEntryFilter(List.of("", "  "), List.of());

        assertThat(filter.accepts("countries/Germany.opml")).isTrue();
    }
}
