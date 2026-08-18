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
 * A source list as exposed over REST: a document that enumerates feeds,
 * plus the defaults applied to everything it imports.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceListDto {

    private String name = "";

    private String title = "";

    private SourceListType type = SourceListType.OPML;

    /** Where the list document is fetched from. */
    private String url = "";

    private boolean enabled = true;

    // ─── Defaults handed to imported sources ───

    /** Applied as the imported source's language override when set. */
    private @Nullable String defaultLanguage;

    private @Nullable String defaultCountry;

    /**
     * Prepended to the categories of every imported source, in front of
     * whatever the list document itself declares.
     */
    private List<String> defaultCategories = new ArrayList<>();

    /** Initial poll interval for imported sources. */
    private @Nullable Long defaultFetchIntervalSeconds;

    /** What happens to imported sources that vanish from the list. */
    private MissingSourcePolicy missingSourcePolicy = MissingSourcePolicy.DISABLE;

    // ─── Refresh state ───

    private long refreshIntervalSeconds;

    private @Nullable Instant nextRefreshAt;

    private @Nullable Instant lastRefreshAt;

    private @Nullable FetchOutcome lastOutcome;

    private @Nullable String lastError;

    private int consecutiveFailures;

    /** Result of the most recent refresh. */
    private @Nullable SourceListRefreshReport lastReport;

    private @Nullable Instant createdAt;

    private @Nullable Instant updatedAt;
}
