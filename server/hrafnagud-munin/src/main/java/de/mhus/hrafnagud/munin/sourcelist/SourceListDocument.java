package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.MissingSourcePolicy;
import de.mhus.hrafnagud.api.source.SourceListRefreshReport;
import de.mhus.hrafnagud.api.source.SourceListType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * A document enumerating feeds, plus how often we re-read it and what it is
 * allowed to do to the registry.
 *
 * <p>Like a source, its identity is the normalised URL and
 * {@link #nextRefreshAt} doubles as the claim lease.
 */
@Document(collection = "source_lists")
@CompoundIndexes({
        @CompoundIndex(name = "url_idx", def = "{ 'url': 1 }", unique = true),
        @CompoundIndex(name = "name_idx", def = "{ 'name': 1 }", unique = true),
        @CompoundIndex(name = "due_idx", def = "{ 'nextRefreshAt': 1 }",
                partialFilter = "{ 'enabled': true }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceListDocument {

    @Id
    private @Nullable String id;

    private String name = "";

    private String title = "";

    private SourceListType type = SourceListType.OPML;

    /** Normalised URL of the list document. Identity. */
    private String url = "";

    private boolean enabled = true;

    // ─── Defaults handed to imported sources ───

    private @Nullable String defaultLanguage;

    private @Nullable String defaultCountry;

    @Builder.Default
    private List<String> defaultCategories = new ArrayList<>();

    private @Nullable Long defaultFetchIntervalSeconds;

    private MissingSourcePolicy missingSourcePolicy = MissingSourcePolicy.DISABLE;

    // ─── Refresh state ───

    private long refreshIntervalSeconds;

    /** Next eligible refresh; pushed forward on claim to act as the lease. */
    private @Nullable Instant nextRefreshAt;

    private @Nullable Instant lastRefreshAt;

    private @Nullable FetchOutcome lastOutcome;

    private @Nullable String lastError;

    private int consecutiveFailures;

    /**
     * Validators from the last successful read. Directories are large and
     * change rarely, so a conditional request usually turns a daily refresh
     * into a 304 — which also means the reconciliation step is skipped, and
     * that is correct: a document we did not read cannot tell us a source
     * has been dropped from it.
     */
    private @Nullable String httpEtag;

    private @Nullable String httpLastModified;

    private @Nullable SourceListRefreshReport lastReport;

    private Instant createdAt = Instant.EPOCH;

    private Instant updatedAt = Instant.EPOCH;

    @Version
    private @Nullable Long version;
}
