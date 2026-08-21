package de.mhus.hrafnagud.api.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What one poll of a source did.
 *
 * <p>{@code itemsRead} minus {@code articlesCreated} minus
 * {@code duplicatesInFeed} is the deduplication yield: entries this feed
 * carried that another source had already delivered. On agency-driven feeds
 * that number dominates, which is the whole reason the dedup key exists.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceFetchReport {

    private String sourceName = "";

    private FetchOutcome outcome = FetchOutcome.OK;

    /** HTTP status of the feed request. Zero when the request never completed. */
    private int httpStatus;

    /** Entries present in the feed document. */
    private int itemsRead;

    /** Entries rejected before dedup — no link, unparseable URL, no title. */
    private int itemsInvalid;

    /** Articles that did not exist before this poll. */
    private int articlesCreated;

    /** Entries that matched an article this same source had already delivered. */
    private int duplicatesInSource;

    /** Entries that matched an article a <em>different</em> source delivered first. */
    private int duplicatesCrossSource;

    /** Poll interval in seconds the adaptive logic picked for the next round. */
    private long nextIntervalSeconds;

    private @Nullable Instant nextFetchAt;

    private @Nullable String error;

    private long durationMillis;
}
