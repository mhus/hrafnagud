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
 * A single news source as exposed over REST.
 *
 * <p>Carries three groups of fields: the configuration a human or a list
 * set, the poll state the ingest loop maintains, and denormalised
 * statistics. They are returned together because the only interesting
 * question about a source ("is this feed actually delivering?") needs all
 * three at once.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceDto {

    /** Stable technical key, unique across all sources. Used in every path. */
    private String name = "";

    /** Display label. Not unique; taken from the feed or list when imported. */
    private String title = "";

    private SourceType type = SourceType.RSS;

    /** Feed URL in normalised form — this is the source's true identity. */
    private String url = "";

    /** Publisher's home page, when the feed declares one. */
    private @Nullable String siteUrl;

    private boolean enabled = true;

    /**
     * Language override for every article from this source, as a BCP-47
     * primary subtag ({@code de}, {@code en}, {@code zh}). When set, it wins
     * over both the feed's declaration and detection.
     */
    private @Nullable String language;

    /** ISO 3166-1 alpha-2 country of the publisher, when known. */
    private @Nullable String country;

    /** Categories applied to every article from this source, verbatim. */
    private List<String> categories = new ArrayList<>();

    // ─── Provenance ───

    private SourceOrigin origin = SourceOrigin.MANUAL;

    /** Name of the source list that imported this source. Null when manual. */
    private @Nullable String originListName;

    /**
     * Fields a human has edited since import. A refresh of the owning list
     * leaves these alone — see {@code POST /sources/{name}/unlock} to give
     * ownership back to the list.
     */
    private List<String> lockedFields = new ArrayList<>();

    /** Last time the owning list still contained this source. */
    private @Nullable Instant lastSeenInListAt;

    // ─── Poll state ───

    /** Current poll interval in seconds, adapted from observed feed activity. */
    private long fetchIntervalSeconds;

    /** When the ingest loop will poll this source next. */
    private @Nullable Instant nextFetchAt;

    private @Nullable Instant lastFetchAt;

    private @Nullable FetchOutcome lastOutcome;

    /** Message of the last failure. Cleared on the next successful poll. */
    private @Nullable String lastError;

    /**
     * Consecutive failed polls. Never auto-disables the source — it only
     * stretches the interval, and is the number an operator sorts by to
     * find dead feeds.
     */
    private int consecutiveFailures;

    // ─── Statistics ───

    /** Articles first seen through this source. Counts deduplicated hits once. */
    private long articleCount;

    /** {@code firstSeenAt} of the most recent article from this source. */
    private @Nullable Instant lastArticleAt;

    private @Nullable Instant createdAt;

    private @Nullable Instant updatedAt;
}
