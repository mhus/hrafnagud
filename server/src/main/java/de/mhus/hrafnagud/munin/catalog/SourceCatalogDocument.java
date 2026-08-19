package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.catalog.CatalogRefreshReport;
import de.mhus.hrafnagud.api.catalog.MissingListPolicy;
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
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A directory of source lists, plus how often we re-read it and what it may
 * do to the lists it owns.
 *
 * <p>The third and outermost layer: catalogue → source lists → sources. Each
 * layer has the same shape — identity by URL, a refresh schedule that doubles
 * as a claim lease, a policy for entries that vanish, and a report of the last
 * pass. That repetition is deliberate; an operator who has understood one
 * layer has understood all three.
 */
@Document(collection = "source_catalogs")
@CompoundIndexes({
        @CompoundIndex(name = "catalog_url_idx", def = "{ 'url': 1 }", unique = true),
        @CompoundIndex(name = "catalog_name_idx", def = "{ 'name': 1 }", unique = true),
        @CompoundIndex(name = "catalog_due_idx", def = "{ 'nextRefreshAt': 1 }",
                partialFilter = "{ 'enabled': true }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceCatalogDocument {

    @Id
    private @Nullable String id;

    private String name = "";

    private String title = "";

    /**
     * Reader id, e.g. {@code opml-directory}. A string and not an enum: the
     * readers are an SPI, and an enum here would mean the storage layer has
     * to change before anyone can add one.
     */
    private String type = "";

    /** Normalised URL of the directory. Identity. */
    private String url = "";

    /** Reader-specific settings. Interpreted only by the reader. */
    @Builder.Default
    private Map<String, String> params = new LinkedHashMap<>();

    /**
     * Whether the periodic refresh picks this catalogue up.
     *
     * <p>A manual refresh ignores it. The flag says "keep this in step by
     * itself", not "this catalogue is off limits", and conflating the two
     * would make the console's button lie about what it does.
     */
    private boolean enabled = true;

    // ─── Selection ───

    @Builder.Default
    private List<String> include = new ArrayList<>();

    @Builder.Default
    private List<String> exclude = new ArrayList<>();

    // ─── Defaults handed to imported lists ───

    private @Nullable Long listRefreshIntervalSeconds;

    /**
     * Interval class for everything this catalogue brings in.
     *
     * <p>The reason the name exists at all: a catalogue is where somebody
     * already knows what kind of sources these are. Saying it here means the
     * lists and their feeds inherit it instead of each being told again.
     */
    private @Nullable String fetchProfile;

    /**
     * Starting poll interval for the sources, when the profile's default is
     * not what this particular collection wants. Clamped into the profile's
     * window like any other interval.
     */
    private @Nullable Long sourceFetchIntervalSeconds;

    private MissingListPolicy missingListPolicy = MissingListPolicy.DISABLE;

    // ─── Refresh state ───

    private long refreshIntervalSeconds;

    /** Next eligible refresh; pushed forward on claim to act as the lease. */
    private @Nullable Instant nextRefreshAt;

    private @Nullable Instant lastRefreshAt;

    private @Nullable FetchOutcome lastOutcome;

    private @Nullable String lastError;

    private int consecutiveFailures;

    /**
     * Hash over the entry URLs of the last successful read.
     *
     * <p>A fingerprint rather than an HTTP ETag, because a catalogue is not
     * necessarily one document: the GitHub reader assembles its answer from
     * several API calls and there is no single validator to keep. An
     * unchanged fingerprint means the same thing a 304 means one layer down
     * and is treated the same way — skip everything, <b>including
     * reconciliation</b>, because a set we did not act on cannot support the
     * conclusion that an entry was dropped.
     */
    private @Nullable String fingerprint;

    private @Nullable CatalogRefreshReport lastReport;

    /**
     * Whether this catalogue was installed by the application rather than by
     * a person. Only read at startup, to decide whether a bundled catalogue
     * still needs installing — never to restrict what an operator may change
     * about it afterwards.
     */
    private boolean bundled;

    private Instant createdAt = Instant.EPOCH;

    private Instant updatedAt = Instant.EPOCH;

    @Version
    private @Nullable Long version;
}
