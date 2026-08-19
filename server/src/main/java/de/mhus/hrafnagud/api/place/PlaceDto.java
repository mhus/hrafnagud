package de.mhus.hrafnagud.api.place;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One place of the containment hierarchy.
 *
 * <p>Served as a table so that a client can turn the ids an article carries
 * into something readable. The names live here and not on the article, because
 * an article is not the right place for a label that depends on who is reading
 * — see specs/geo.md §7.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlaceDto {

    /** Scheme-prefixed: {@code m49:142}, {@code iso:SG}. */
    private String id = "";

    private @Nullable String parentId;

    /** {@code WORLD}, {@code REGION}, {@code SUBREGION}, … */
    private String kind = "";

    /** English name. Display only; not stable enough to key on. */
    private String name = "";

    /** This place and everything containing it, outermost first. */
    @Builder.Default
    private List<String> path = new ArrayList<>();
}
