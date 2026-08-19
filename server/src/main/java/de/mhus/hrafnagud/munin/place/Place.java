package de.mhus.hrafnagud.munin.place;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One place in the containment hierarchy.
 *
 * @param id       scheme-prefixed identifier, {@code m49:142} or {@code iso:SG}.
 *                 Prefixed so that a gazetteer added later cannot collide with
 *                 this one — see specs/geo.md §3.2.
 * @param parentId the place directly containing this one, null for the world.
 * @param kind     how deep in the ladder this sits.
 * @param name     English name, for diagnostics. <b>Not</b> what a reader is
 *                 shown: display names depend on the reader's language, which
 *                 is not a property of the place.
 * @param path     this place and everything containing it, outermost first.
 *                 Materialised at load time so that neither a query nor a write
 *                 has to walk the tree.
 */
public record Place(
        String id,
        @Nullable String parentId,
        PlaceKind kind,
        String name,
        List<String> path) {

    public Place {
        path = List.copyOf(path);
    }
}
