package de.mhus.hrafnagud.api.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What one source-list refresh did. Returned by the manual refresh endpoint
 * and stored on the list so the last result stays visible.
 *
 * <p>{@code skipped} is the number that is worth watching: it counts
 * entries the list wanted to change but could not, because a human had
 * edited the field or the source belongs to somebody else.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceListRefreshReport {

    private FetchOutcome outcome = FetchOutcome.OK;

    /** Entries the list document contained. */
    private int entriesFound;

    /** Sources newly created. */
    private int created;

    /** Existing sources whose fields the refresh changed. */
    private int updated;

    /** Existing sources that already agreed with the list. The normal case. */
    private int unchanged;

    /**
     * Existing sources the refresh was not allowed to touch: every field it
     * wanted to write is locked by a human edit, or the source belongs to a
     * different list. A number that climbs here means the list and the
     * operator disagree, which is worth looking at.
     */
    private int skipped;

    /** Sources disabled or deleted per {@link MissingSourcePolicy}. */
    private int removed;

    /** Entries rejected as unusable — no URL, unparseable URL, duplicate. */
    private int invalid;

    /** Up to a handful of human-readable notes about rejected entries. */
    private List<String> warnings = new ArrayList<>();

    private @Nullable String error;

    private @Nullable Instant finishedAt;
}
