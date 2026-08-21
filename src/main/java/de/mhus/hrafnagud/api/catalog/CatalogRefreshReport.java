package de.mhus.hrafnagud.api.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.hrafnagud.api.source.FetchOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What one catalogue refresh did to the source lists.
 *
 * <p>Same shape as {@code SourceListRefreshReport} one layer down, and for
 * the same reason: the interesting question after a refresh is never "did it
 * work" alone but "what changed", and a count per outcome is the smallest
 * answer that supports it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogRefreshReport {

    private FetchOutcome outcome = FetchOutcome.OK;

    /** Entries the catalogue offered, before the include/exclude filter. */
    private int entriesFound;

    /** Entries left after the filter — the ones this refresh acted on. */
    private int entriesSelected;

    private int created;

    private int updated;

    private int unchanged;

    /**
     * Entries not acted on: an unusable URL, or a list already owned by a
     * different catalogue. Not an error — see {@code SourceCatalogService}.
     */
    private int skipped;

    /** Lists the catalogue stopped offering, handled per {@link MissingListPolicy}. */
    private int removed;

    private int invalid;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    private @Nullable String error;

    private @Nullable Instant finishedAt;
}
