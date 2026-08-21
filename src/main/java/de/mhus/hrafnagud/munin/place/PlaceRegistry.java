package de.mhus.hrafnagud.munin.place;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The containment hierarchy, loaded once from a bundled table.
 *
 * <p>World → region → sub-region → intermediate region → country, from UN M.49
 * above the country and ISO 3166-1 at it. About 280 rows: small enough to hold
 * in memory, stable enough to ship, and complete enough that the common
 * question — "everything about Asia" — needs no gazetteer, no download and no
 * network.
 *
 * <p>Immutable after startup. A place's ancestors are computed once at load and
 * stored on it, so producing the path for an article is a map lookup rather
 * than a walk. See specs/geo.md §3.2 for why the path is materialised at all.
 */
@Component
@Slf4j
public class PlaceRegistry {

    static final String RESOURCE = "places/m49-hierarchy.tsv";

    /** The root every path starts at. */
    public static final String WORLD = "m49:001";

    public static final String COUNTRY_PREFIX = "iso:";

    private Map<String, Place> byId = Map.of();

    @PostConstruct
    void load() {
        Map<String, Row> rows = readRows();
        Map<String, Place> places = new LinkedHashMap<>();
        for (Row row : rows.values()) {
            places.put(row.id, new Place(row.id, row.parentId, row.kind, row.name,
                    pathOf(row, rows)));
        }
        byId = Collections.unmodifiableMap(places);
        log.info("Place hierarchy loaded: {} places, {} countries", byId.size(),
                byId.values().stream().filter(p -> p.kind() == PlaceKind.COUNTRY).count());
    }

    /** The place, if this registry knows it. */
    public Optional<Place> find(@Nullable String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /**
     * The place for an ISO 3166-1 alpha-2 country code.
     *
     * <p>Case-insensitive and tolerant of surrounding space, because the code
     * arrives from a hand-edited list default as often as from a machine.
     */
    public Optional<Place> forCountry(@Nullable String alpha2) {
        String trimmed = StringUtils.trimToNull(alpha2);
        return trimmed == null
                ? Optional.empty()
                : find(COUNTRY_PREFIX + trimmed.toUpperCase(Locale.ROOT));
    }

    /**
     * Ancestor path for a country code, outermost first, or empty when the code
     * is unknown.
     *
     * <p>Empty rather than a guess: an unrecognised country code is a
     * configuration mistake, and inventing {@code [World]} for it would put the
     * article into every containment query that asks for the world while saying
     * nothing true about it.
     */
    public List<String> pathForCountry(@Nullable String alpha2) {
        return forCountry(alpha2).map(Place::path).orElseGet(List::of);
    }

    /** Every place, ordered outermost first — for the API and diagnostics. */
    public List<Place> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    private static List<String> pathOf(Row row, Map<String, Row> rows) {
        List<String> path = new ArrayList<>();
        Row current = row;
        // Bounded: a malformed table with a parent cycle must not hang startup,
        // and the real ladder is five rungs deep.
        for (int depth = 0; current != null && depth < 16; depth++) {
            path.add(current.id);
            current = current.parentId == null ? null : rows.get(current.parentId);
        }
        Collections.reverse(path);
        return path;
    }

    private Map<String, Row> readRows() {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 4) {
                    log.warn("Place table: skipping malformed line '{}'", line);
                    continue;
                }
                String parent = "-".equals(parts[1]) ? null : parts[1];
                rows.put(parts[0], new Row(parts[0], parent,
                        PlaceKind.valueOf(parts[2]), parts[3]));
            }
        } catch (IOException | RuntimeException e) {
            // Fatal on purpose. The table is a bundled resource, so a failure
            // here means the build is broken rather than the environment, and
            // starting with an empty hierarchy would silently drop every
            // article's origin path instead.
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
        return rows;
    }

    private record Row(String id, @Nullable String parentId, PlaceKind kind, String name) { }
}
