package de.mhus.hrafnagud.facet;

import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.category.Topic;
import de.mhus.hrafnagud.munin.category.TopicRegistry;
import de.mhus.hrafnagud.munin.place.Place;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
import de.mhus.vance.ode.facet.OdeFacet;
import de.mhus.vance.ode.facet.OdeFacetValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What a reader may filter the archive by, and how a selection becomes a
 * query.
 *
 * <p>Two dimensions, both already stored as materialised paths, so a reader
 * asks for one node and the query answers for every rung below it:
 *
 * <ul>
 *   <li><b>{@code origin-place}</b> — where the publisher sits, from
 *       {@code originPlaceIds}. Explicitly <em>not</em> what the article is
 *       about; see {@code specs/geo.md} §1 for why conflating the two produces
 *       a filter that is wrong exactly where it matters.
 *   <li><b>{@code subject-topic}</b> — what it is about, from
 *       {@code topicIds}, normalised against IPTC Media Topics
 *       ({@code specs/categories.md}). The verbatim publisher categories are
 *       not offered: 7,365 distinct strings, of which some are places and some
 *       are a person's name, make a picker nobody can use and a filter that
 *       finds a third of what it claims.
 * </ul>
 *
 * <p>Both are declared to both contracts. A feed reader filtering a timeline
 * and a research query filtering a ranked answer ask the same question of the
 * same field; declaring it once here is what keeps the two answers the same.
 */
@Service
@RequiredArgsConstructor
public class ArchiveFacets {

    public static final String ORIGIN_PLACE = "origin-place";
    public static final String SUBJECT_TOPIC = "subject-topic";

    private final PlaceRegistry places;
    private final TopicRegistry topics;

    /**
     * The declaration.
     *
     * <p>Places travel inline — the M.49/ISO table is a few hundred entries
     * and changes on the timescale of countries splitting. Media Topics do
     * not: about 1,400 concepts is past what belongs in every capabilities
     * response, so that tree is served a level at a time and the declaration
     * carries only its roots.
     */
    public List<OdeFacet> declare() {
        return List.of(
                OdeFacet.tree(ORIGIN_PLACE, "Publisher's place", placeValues()),
                new OdeFacet(SUBJECT_TOPIC, "Topic", true, topicRoots(), true));
    }

    /**
     * One level of a facet's tree. {@code parentId} null is the top level.
     *
     * <p>Answers for places too, even though they travelled inline: a reader
     * that asks anyway should get the same tree, not an empty list that reads
     * as „this facet has no values".
     */
    public List<OdeFacetValue> values(String key, @Nullable String parentId) {
        return switch (key) {
            case ORIGIN_PLACE -> parentId == null
                    ? placeValues()
                    : placeValues().stream().filter(v -> parentId.equals(v.parentId())).toList();
            case SUBJECT_TOPIC -> topicChildren(parentId);
            default -> List.of();
        };
    }

    /**
     * Fold a facet selection into a query.
     *
     * <p>Only keys we declared arrive here — the contract narrows the map
     * before the source sees it — so an unknown key is dropped rather than
     * refused, which keeps this side working when a newer reader learns a
     * facet we have not built yet.
     */
    public ArticleQuery.ArticleQueryBuilder apply(
            ArticleQuery.ArticleQueryBuilder builder, Map<String, List<String>> facets) {
        List<String> selectedPlaces = facets.get(ORIGIN_PLACE);
        if (selectedPlaces != null && !selectedPlaces.isEmpty()) {
            builder.originPlaces(selectedPlaces);
        }
        List<String> selectedTopics = facets.get(SUBJECT_TOPIC);
        if (selectedTopics != null && !selectedTopics.isEmpty()) {
            builder.topics(selectedTopics);
        }
        return builder;
    }

    private List<OdeFacetValue> placeValues() {
        List<Place> all = places.all();
        List<OdeFacetValue> out = new ArrayList<>(all.size());
        for (Place place : all) {
            out.add(new OdeFacetValue(place.id(), place.name(), place.parentId()));
        }
        return List.copyOf(out);
    }

    private List<OdeFacetValue> topicRoots() {
        return topicChildren(null);
    }

    private List<OdeFacetValue> topicChildren(@Nullable String parentId) {
        List<OdeFacetValue> out = new ArrayList<>();
        for (Topic topic : topics.all()) {
            if (parentId == null ? topic.parentId() == null : parentId.equals(topic.parentId())) {
                out.add(new OdeFacetValue(topic.id(), topic.name(), topic.parentId()));
            }
        }
        return List.copyOf(out);
    }
}
