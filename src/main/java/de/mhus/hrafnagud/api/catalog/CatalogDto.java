package de.mhus.hrafnagud.api.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.hrafnagud.api.source.FetchOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A catalogue: something that enumerates source <em>lists</em>, one layer
 * above the lists that enumerate feeds.
 *
 * <p>Three layers, each the same shape — a document with a refresh schedule
 * that reconciles the layer below it. Catalogue → lists → sources.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogDto {

    /** Stable technical key, unique. Used in every path. */
    private String name = "";

    private String title = "";

    /**
     * Which reader resolves this catalogue — {@code opml-directory} or
     * {@code github-opml}. A string rather than an enum: readers are an SPI,
     * and a closed enum in the contract would mean a new reader cannot be
     * added without changing this module.
     */
    private String type = "";

    /**
     * Where the catalogue lives. Its meaning belongs to the reader: an OPML
     * directory URL for {@code opml-directory}, a repository web URL for
     * {@code github-opml}.
     */
    private String url = "";

    /** Reader-specific settings, e.g. {@code paths} for {@code github-opml}. */
    @Builder.Default
    private Map<String, String> params = new LinkedHashMap<>();

    /**
     * Whether the periodic refresh picks this up. A manual refresh runs
     * regardless — an explicit request is not a schedule.
     */
    private boolean enabled;

    // ─── Selection ───

    /**
     * Globs against the entry name. Empty means every entry.
     *
     * <p>The filter lives on the catalogue and not in a UI, because the
     * periodic refresh would otherwise re-import everything the moment
     * nobody was looking.
     */
    @Builder.Default
    private List<String> include = new ArrayList<>();

    /** Globs that veto, applied after {@link #include}. */
    @Builder.Default
    private List<String> exclude = new ArrayList<>();

    // ─── Defaults handed to imported lists ───

    private @Nullable Long listRefreshIntervalSeconds;

    /**
     * Named interval class handed down to lists and sources. Null means the
     * default — a name here is how "these are blogs" is said once.
     */
    private @Nullable String fetchProfile;

    private @Nullable Long sourceFetchIntervalSeconds;

    private MissingListPolicy missingListPolicy = MissingListPolicy.DISABLE;

    // ─── Refresh state ───

    private long refreshIntervalSeconds;

    private @Nullable Instant nextRefreshAt;

    private @Nullable Instant lastRefreshAt;

    private @Nullable FetchOutcome lastOutcome;

    private @Nullable String lastError;

    private int consecutiveFailures;

    /** Source lists this catalogue currently owns. */
    private long listCount;

    private @Nullable CatalogRefreshReport lastReport;

    private @Nullable Instant createdAt;

    private @Nullable Instant updatedAt;
}
