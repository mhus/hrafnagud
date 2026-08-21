package de.mhus.hrafnagud.munin.place;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bundled hierarchy and the paths it produces.
 *
 * <p>The table is a build artefact, so these are as much a check on the
 * generated file as on the code reading it: a missing rung would not fail
 * anything at startup, it would quietly produce short paths and articles that
 * no containment query finds.
 */
class PlaceRegistryTest {

    private PlaceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PlaceRegistry();
        registry.load();
    }

    @Test
    void the_table_covers_every_country_and_the_ladder_above_it() {
        assertThat(registry.size()).isGreaterThan(270);
        assertThat(registry.all()).filteredOn(p -> p.kind() == PlaceKind.REGION).hasSize(5);
        assertThat(registry.all()).filteredOn(p -> p.kind() == PlaceKind.COUNTRY)
                .hasSizeGreaterThan(240);
    }

    /** The example from the spec, end to end. */
    @Test
    void singapore_resolves_to_world_asia_south_eastern_asia_singapore() {
        assertThat(registry.pathForCountry("SG")).containsExactly(
                "m49:001", "m49:142", "m49:035", "iso:SG");
    }

    @Test
    void a_country_under_an_intermediate_region_carries_all_five_rungs() {
        // Kenya sits in Eastern Africa (014), inside Sub-Saharan Africa (202),
        // inside Africa (002) — the deepest shape the table has.
        assertThat(registry.pathForCountry("KE")).containsExactly(
                "m49:001", "m49:002", "m49:202", "m49:014", "iso:KE");
    }

    @Test
    void the_path_always_starts_at_the_world_and_ends_at_the_country() {
        for (Place place : registry.all()) {
            if (place.kind() != PlaceKind.COUNTRY) {
                continue;
            }
            assertThat(place.path()).startsWith(PlaceRegistry.WORLD);
            assertThat(place.path()).endsWith(place.id());
        }
    }

    @Test
    void country_codes_are_case_insensitive_and_trimmed() {
        List<String> expected = registry.pathForCountry("DE");

        assertThat(registry.pathForCountry("de")).isEqualTo(expected);
        assertThat(registry.pathForCountry("  De  ")).isEqualTo(expected);
    }

    /**
     * An unknown code yields nothing rather than the world. Inventing a root
     * would put the article into every containment query while saying nothing
     * true about it.
     */
    @Test
    void an_unknown_or_missing_country_yields_an_empty_path() {
        assertThat(registry.pathForCountry("XX")).isEmpty();
        assertThat(registry.pathForCountry(null)).isEmpty();
        assertThat(registry.pathForCountry("  ")).isEmpty();
    }

    /**
     * Two areas carry no M.49 region at all. They must still resolve, or the
     * loader has silently dropped a country.
     */
    @Test
    void areas_without_a_region_hang_directly_under_the_world() {
        assertThat(registry.pathForCountry("AQ")).containsExactly("m49:001", "iso:AQ");
        assertThat(registry.forCountry("TW")).isPresent();
    }

    @Test
    void every_parent_reference_resolves() {
        for (Place place : registry.all()) {
            if (place.parentId() == null) {
                assertThat(place.id()).isEqualTo(PlaceRegistry.WORLD);
                continue;
            }
            assertThat(registry.find(place.parentId()))
                    .as("parent %s of %s", place.parentId(), place.id())
                    .isPresent();
        }
    }
}
