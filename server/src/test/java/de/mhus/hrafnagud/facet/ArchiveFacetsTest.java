package de.mhus.hrafnagud.facet;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.category.TestTopics;
import de.mhus.hrafnagud.munin.place.TestPlaces;
import de.mhus.vance.ode.facet.OdeFacet;
import de.mhus.vance.ode.facet.OdeFacetValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArchiveFacetsTest {

    private ArchiveFacets facets;

    @BeforeEach
    void setUp() {
        facets = new ArchiveFacets(TestPlaces.loaded(), TestTopics.loaded());
    }

    @Test
    void places_travelInlineBecauseTheTableIsSmallAndStable() {
        OdeFacet place = declared(ArchiveFacets.ORIGIN_PLACE);

        assertThat(place.hierarchical()).isTrue();
        assertThat(place.lazyChildren()).isFalse();
        assertThat(place.values()).isNotEmpty();
        assertThat(place.values())
                .anySatisfy(v -> assertThat(v.id()).isEqualTo("iso:SG"));
    }

    @Test
    void topics_areServedLevelByLevelBecauseTheVocabularyIsTooLargeToShip() {
        OdeFacet topic = declared(ArchiveFacets.SUBJECT_TOPIC);

        assertThat(topic.lazyChildren()).isTrue();
        // Only the roots travel; everything below is asked for.
        assertThat(topic.values()).isNotEmpty();
        assertThat(topic.values())
                .allSatisfy(v -> assertThat(v.parentId()).isNull());
    }

    @Test
    void aTopicLevelIsResolvedByParent() {
        String root = declared(ArchiveFacets.SUBJECT_TOPIC).values().get(0).id();

        List<OdeFacetValue> children = facets.values(ArchiveFacets.SUBJECT_TOPIC, root);

        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(v -> assertThat(v.parentId()).isEqualTo(root));
    }

    @Test
    void placeValuesAreAnsweredEvenThoughTheyAlreadyTravelled() {
        // An empty answer here would read as "this facet has no values",
        // which is a different statement from "you already have them".
        assertThat(facets.values(ArchiveFacets.ORIGIN_PLACE, "m49:035"))
                .anySatisfy(v -> assertThat(v.id()).isEqualTo("iso:SG"));
    }

    @Test
    void aSelectionBecomesTheQueryFieldsThatHoldTheMaterialisedPaths() {
        ArticleQuery query = facets.apply(
                ArticleQuery.builder(),
                Map.of(ArchiveFacets.ORIGIN_PLACE, List.of("m49:142"),
                        ArchiveFacets.SUBJECT_TOPIC, List.of("medtop:15000000"))).build();

        assertThat(query.getOriginPlaces()).containsExactly("m49:142");
        assertThat(query.getTopics()).containsExactly("medtop:15000000");
    }

    @Test
    void anUnknownKeyIsIgnoredRatherThanRefused() {
        ArticleQuery query = facets.apply(
                ArticleQuery.builder(), Map.of("mood", List.of("calm"))).build();

        assertThat(query.getOriginPlaces()).isNull();
        assertThat(query.getTopics()).isNull();
    }

    // ─── accepted ───

    @Test
    void acceptedIsFlatAndTwoValuedSoTheAuditDirectionExistsToo() {
        OdeFacet accepted = declared(ArchiveFacets.ACCEPTED);

        assertThat(accepted.hierarchical()).isFalse();
        assertThat(accepted.values()).extracting(OdeFacetValue::id)
                .containsExactly("yes", "no");
    }

    @Test
    void selectingAcceptedNarrowsToTheTranslationDecision() {
        ArticleQuery inScope = facets.apply(ArticleQuery.builder(),
                Map.of(ArchiveFacets.ACCEPTED, List.of("yes"))).build();
        ArticleQuery filteredOut = facets.apply(ArticleQuery.builder(),
                Map.of(ArchiveFacets.ACCEPTED, List.of("no"))).build();

        assertThat(inScope.getTranslationPolicy()).isEqualTo(FilterDecision.ACCEPT);
        assertThat(filteredOut.getTranslationPolicy()).isEqualTo(FilterDecision.DENY);
    }

    /**
     * Both values is the same request as neither. Treating it as a filter would
     * make the reader's "I want everything" narrower than not asking.
     */
    @Test
    void selectingBothValuesFiltersNothing() {
        ArticleQuery query = facets.apply(ArticleQuery.builder(),
                Map.of(ArchiveFacets.ACCEPTED, List.of("yes", "no"))).build();

        assertThat(query.getTranslationPolicy()).isNull();
    }

    @Test
    void notAskingServesEverything() {
        ArticleQuery query = facets.apply(ArticleQuery.builder(), Map.of()).build();

        assertThat(query.getTranslationPolicy()).isNull();
    }

    private OdeFacet declared(String key) {
        return facets.declare().stream()
                .filter(f -> f.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
