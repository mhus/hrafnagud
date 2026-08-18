package de.mhus.hrafnagud.munin.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The merge rule is what makes a directory-fed registry survivable: an
 * operator's correction has to outlive the next refresh, or nobody will
 * bother making one.
 */
class SourceMergePolicyTest {

    private static final SourceMergePolicy.Defaults NO_DEFAULTS =
            SourceMergePolicy.Defaults.builder().build();

    private static SourceDocument existing() {
        return SourceDocument.builder()
                .name("example-abc123")
                .title("Old Title")
                .url("https://example.com/rss")
                .enabled(true)
                .categories(List.of("Old"))
                .lockedFields(new LinkedHashSet<>())
                .build();
    }

    private static SourceCandidate candidate() {
        return SourceCandidate.builder()
                .url("https://example.com/rss")
                .title("New Title")
                .siteUrl("https://example.com/")
                .categories(List.of("News", "Germany"))
                .language("de")
                .build();
    }

    @Test
    void apply_updatesUnlockedFields() {
        SourceDocument target = existing();

        Set<String> changed = SourceMergePolicy.apply(target, candidate(), NO_DEFAULTS);

        assertThat(target.getTitle()).isEqualTo("New Title");
        assertThat(target.getLanguage()).isEqualTo("de");
        assertThat(target.getCategories()).containsExactly("News", "Germany");
        assertThat(changed).contains(SourceMergePolicy.FIELD_TITLE,
                SourceMergePolicy.FIELD_LANGUAGE, SourceMergePolicy.FIELD_CATEGORIES);
    }

    @Test
    void apply_leavesLockedFieldsAlone() {
        SourceDocument target = existing();
        target.setLockedFields(new LinkedHashSet<>(Set.of(SourceMergePolicy.FIELD_TITLE)));

        Set<String> changed = SourceMergePolicy.apply(target, candidate(), NO_DEFAULTS);

        assertThat(target.getTitle()).isEqualTo("Old Title");
        assertThat(changed).doesNotContain(SourceMergePolicy.FIELD_TITLE);
    }

    @Test
    void apply_reEnablesASourceTheListStillCarries() {
        // A feed the directory dropped and later restored comes back on its
        // own; that is the point of the DISABLE policy being non-destructive.
        SourceDocument target = existing();
        target.setEnabled(false);

        Set<String> changed = SourceMergePolicy.apply(target, candidate(), NO_DEFAULTS);

        assertThat(target.isEnabled()).isTrue();
        assertThat(changed).contains(SourceMergePolicy.FIELD_ENABLED);
    }

    @Test
    void apply_doesNotReEnableWhenAHumanDisabledIt() {
        // The single most important case: an operator who switches off a
        // feed publishing garbage must not have to do it again every day.
        SourceDocument target = existing();
        target.setEnabled(false);
        target.setLockedFields(new LinkedHashSet<>(Set.of(SourceMergePolicy.FIELD_ENABLED)));

        Set<String> changed = SourceMergePolicy.apply(target, candidate(), NO_DEFAULTS);

        assertThat(target.isEnabled()).isFalse();
        assertThat(changed).doesNotContain(SourceMergePolicy.FIELD_ENABLED);
    }

    @Test
    void apply_reportsNoChangeWhenEverythingAlreadyAgrees() {
        SourceDocument target = existing();
        SourceMergePolicy.apply(target, candidate(), NO_DEFAULTS);

        Set<String> secondRun = SourceMergePolicy.apply(target, candidate(), NO_DEFAULTS);

        assertThat(secondRun).isEmpty();
    }

    @Test
    void apply_blankCandidateValues_doNotWipeExistingOnes() {
        SourceDocument target = existing();
        SourceCandidate sparse = SourceCandidate.builder()
                .url("https://example.com/rss")
                .title("")
                .build();

        SourceMergePolicy.apply(target, sparse, NO_DEFAULTS);

        assertThat(target.getTitle()).isEqualTo("Old Title");
    }

    @Test
    void apply_listDefaults_fillInWhatTheEntryDoesNotDeclare() {
        SourceDocument target = existing();
        SourceCandidate withoutLanguage = SourceCandidate.builder()
                .url("https://example.com/rss")
                .title("T")
                .build();
        SourceMergePolicy.Defaults defaults = SourceMergePolicy.Defaults.builder()
                .language("fr")
                .country("FR")
                .build();

        SourceMergePolicy.apply(target, withoutLanguage, defaults);

        assertThat(target.getLanguage()).isEqualTo("fr");
        assertThat(target.getCountry()).isEqualTo("FR");
    }

    @Test
    void apply_entryLanguage_winsOverTheListDefault() {
        SourceDocument target = existing();
        SourceMergePolicy.Defaults defaults =
                SourceMergePolicy.Defaults.builder().language("fr").build();

        SourceMergePolicy.apply(target, candidate(), defaults);

        assertThat(target.getLanguage()).isEqualTo("de");
    }

    @Test
    void mergeCategories_putsDefaultsFirstAndDeduplicates() {
        List<String> merged = SourceMergePolicy.mergeCategories(
                List.of("Curated", "News"), List.of("News", "Germany"));

        assertThat(merged).containsExactly("Curated", "News", "Germany");
    }

    @Test
    void mergeCategories_dropsBlanksAndTrims() {
        List<String> merged = SourceMergePolicy.mergeCategories(
                List.of("  ", "A "), List.of("", " B"));

        assertThat(merged).containsExactly("A", "B");
    }
}
