package de.mhus.hrafnagud.munin.category;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One IPTC Media Topic.
 *
 * @param id       qcode, {@code medtop:15000000}. Prefixed like a place id, so
 *                 a second vocabulary could never be confused with this one.
 * @param parentId the topic directly containing this one, null for a root.
 * @param name     English name. Diagnostics only — a label shown to a reader
 *                 depends on their language, which is not a property of the
 *                 topic as stored.
 * @param path     this topic and everything containing it, outermost first.
 *                 Materialised at load, so "everything about sport" costs an
 *                 equality match rather than a tree walk.
 * @param labels   every label of this concept in all thirteen languages,
 *                 normalised for matching. This is what stage one searches.
 */
public record Topic(
        String id,
        @Nullable String parentId,
        String name,
        List<String> path,
        Set<String> labels) {

    public Topic {
        path = List.copyOf(path);
        labels = Set.copyOf(labels);
    }
}
