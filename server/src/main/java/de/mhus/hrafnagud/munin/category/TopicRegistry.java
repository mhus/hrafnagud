package de.mhus.hrafnagud.munin.category;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The IPTC Media Topics vocabulary, loaded once from a bundled table.
 *
 * <p>1,393 concepts, five levels, seventeen roots, and about ten thousand
 * labels across thirteen languages. Bundled rather than fetched: it changes on
 * the timescale of a standards committee, and a classifier that stops working
 * when a remote service is down would be worse than one that is a release
 * behind.
 *
 * <p>Two indexes are built at load. The tree, so a topic knows its ancestors;
 * and label → topic across every language, which is what makes
 * {@code Wirtschaft und Finanzen} resolve to the same concept as
 * {@code economy, business and finance} without anybody translating anything.
 */
@Component
@Slf4j
public class TopicRegistry {

    static final String RESOURCE = "topics/iptc-mediatopics.tsv";

    private Map<String, Topic> byId = Map.of();
    private Map<String, String> byLabel = Map.of();

    @PostConstruct
    void load() {
        Map<String, Row> rows = readRows();

        Map<String, Topic> topics = new LinkedHashMap<>();
        for (Row row : rows.values()) {
            topics.put(row.id, new Topic(row.id, row.parentId, row.name,
                    pathOf(row, rows), row.labels));
        }
        byId = Collections.unmodifiableMap(topics);

        // First label wins. Collisions are real — several concepts carry the
        // label "football" in different languages — and picking the first in a
        // stable file order at least makes the outcome reproducible rather
        // than dependent on hash iteration.
        Map<String, String> labels = new HashMap<>();
        for (Topic topic : topics.values()) {
            for (String label : topic.labels()) {
                labels.putIfAbsent(label, topic.id());
            }
        }
        byLabel = Collections.unmodifiableMap(labels);

        log.info("Media Topics loaded: {} concepts, {} labels, {} roots", byId.size(),
                byLabel.size(),
                byId.values().stream().filter(t -> t.parentId() == null).count());
    }

    public Optional<Topic> find(@Nullable String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** The topic whose label equals this normalised string, if any. */
    public Optional<Topic> byLabel(@Nullable String normalisedLabel) {
        if (normalisedLabel == null || normalisedLabel.isBlank()) {
            return Optional.empty();
        }
        return find(byLabel.get(normalisedLabel));
    }

    /** Ancestor path for a topic id, outermost first; empty when unknown. */
    public List<String> pathFor(@Nullable String id) {
        return find(id).map(Topic::path).orElseGet(List::of);
    }

    public List<Topic> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /** Every normalised label this build knows — stage one's search space. */
    Map<String, String> labelIndex() {
        return byLabel;
    }

    private static List<String> pathOf(Row row, Map<String, Row> rows) {
        List<String> path = new ArrayList<>();
        Row current = row;
        // Bounded: a malformed table with a cycle must not hang startup, and
        // the real vocabulary is five levels deep.
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
                // -1: a concept with no labels still has its column, empty.
                String[] parts = line.split("\t", -1);
                if (parts.length < 4) {
                    log.warn("Topic table: skipping malformed line '{}'", line);
                    continue;
                }
                Set<String> labels = new LinkedHashSet<>();
                for (String label : parts[3].split("\\|")) {
                    if (!label.isBlank()) {
                        labels.add(label);
                    }
                }
                rows.put(parts[0], new Row(parts[0],
                        "-".equals(parts[1]) ? null : parts[1], parts[2], labels));
            }
        } catch (IOException | RuntimeException e) {
            // Fatal: the table is a bundled resource, so this is a broken
            // build rather than a broken environment, and starting with an
            // empty vocabulary would silently classify nothing for ever.
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
        return rows;
    }

    private record Row(String id, @Nullable String parentId, String name, Set<String> labels) { }
}
