package de.mhus.hrafnagud.munin.source;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.SourceOrigin;
import de.mhus.hrafnagud.api.source.SourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A feed we poll.
 *
 * <p>Identity is the normalised {@link #url}, not the {@link #name} and not
 * the Mongo id. Two entries pointing at the same feed are the same source
 * however they were spelled, which is what stops a directory import from
 * duplicating half the registry when a publisher starts appending a
 * campaign parameter to its own feed link.
 *
 * <p>{@link #lockedFields} is the mechanism that lets a hand-maintained
 * registry and an imported one coexist. A source list is authoritative for
 * everything it imported <em>except</em> the fields a human has since
 * edited; without that, disabling a broken feed would last exactly until
 * the next refresh, which is the failure mode every "sync from upstream"
 * feature eventually grows.
 */
@Document(collection = "sources")
@CompoundIndexes({
        // Identity. Unique so a concurrent create loses at the database
        // rather than producing a second row for one feed.
        @CompoundIndex(name = "url_idx", def = "{ 'url': 1 }", unique = true),
        @CompoundIndex(name = "name_idx", def = "{ 'name': 1 }", unique = true),
        // The ingest loop's claim query: enabled sources that are due,
        // oldest first. Partial-filtered on enabled because disabled
        // sources are never claimed and would only pad the index.
        @CompoundIndex(name = "due_idx", def = "{ 'nextFetchAt': 1 }",
                partialFilter = "{ 'enabled': true }"),
        // Refresh reconciliation: everything one list imported.
        @CompoundIndex(name = "origin_list_idx", def = "{ 'originListName': 1 }"),
        // Operator view: the feeds that are failing, worst first.
        @CompoundIndex(name = "failures_idx", def = "{ 'consecutiveFailures': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceDocument {

    @Id
    private @Nullable String id;

    /** Stable technical key, derived from the URL. Unique (siehe name_idx oben). */
    private String name = "";

    private String title = "";

    private SourceType type = SourceType.RSS;

    /** Normalised feed URL — the source's identity. Unique. */
    private String url = "";

    /**
     * Where the feed is actually fetched from, when a permanent redirect
     * moved it. Null — the normal case — means {@link #url}.
     *
     * <p>This is deliberately <em>not</em> a rewrite of {@link #url}, and the
     * reason is the source list. A list entry is matched to its source by
     * URL, so a rewritten identity is a source the list can no longer find:
     * the next refresh would create a second source for the old URL, and the
     * reconciliation step would disable the repaired one for no longer being
     * in a list it never left. The refresh after that would try the same
     * rewrite again and hit the unique index. Splitting identity from
     * location makes all of it a non-event — the list keeps matching on a
     * field nothing in the ingest path writes.
     *
     * <p>It is also how the rewrite stays reversible: cleared on any failed
     * poll (see {@code SourceService#clearFetchUrl}), so a location that
     * goes stale costs one redirect, not a dead source.
     */
    private @Nullable String fetchUrl;

    private @Nullable String siteUrl;

    private boolean enabled = true;

    /** Language override applied to every article from this source. */
    private @Nullable String language;

    private @Nullable String country;

    /** Applied to every article from this source, in addition to the feed's own. */
    @Builder.Default
    private List<String> categories = new ArrayList<>();

    // ─── Provenance ───

    private SourceOrigin origin = SourceOrigin.MANUAL;

    /** {@code SourceListDocument.name} that imported this source. */
    private @Nullable String originListName;

    /**
     * Fields a human edited after import. A list refresh skips these.
     * Holds document field names, e.g. {@code enabled}, {@code language}.
     */
    @Builder.Default
    private Set<String> lockedFields = new LinkedHashSet<>();

    /**
     * Last refresh in which the owning list still contained this source.
     * The reconciliation step compares it against the refresh timestamp to
     * find sources the list has dropped.
     */
    private @Nullable Instant lastSeenInListAt;

    // ─── Poll state ───

    /**
     * Interval class this source belongs to, or null for the default.
     *
     * <p>Inherited from the list that imported it, which inherits it from the
     * catalogue. A name and not three numbers, so "these are blogs" is said
     * once where the collection is registered.
     */
    private @Nullable String fetchProfile;

    private long fetchIntervalSeconds;

    /**
     * When this source becomes eligible for polling. Doubles as the claim
     * lease: claiming pushes it into the future, so a worker that dies
     * mid-fetch releases the source when the lease expires instead of
     * pinning it forever.
     */
    private @Nullable Instant nextFetchAt;

    private @Nullable Instant lastFetchAt;

    private @Nullable FetchOutcome lastOutcome;

    private @Nullable String lastError;

    private int consecutiveFailures;

    // ─── Conditional-request validators ───

    /** {@code ETag} of the last successful response, replayed as {@code If-None-Match}. */
    private @Nullable String httpEtag;

    /** {@code Last-Modified} of the last successful response. */
    private @Nullable String httpLastModified;

    // ─── Statistics ───

    /** Articles this source delivered first. Incremented on ingest. */
    private long articleCount;

    private @Nullable Instant lastArticleAt;

    private Instant createdAt = Instant.EPOCH;

    private Instant updatedAt = Instant.EPOCH;

    /**
     * Optimistic locking for the read-modify-write paths (REST updates,
     * list merges). The ingest loop does not rely on it — it uses
     * conditional updates instead, which do not need the document in hand.
     */
    @Version
    private @Nullable Long version;

    /**
     * The URL to poll: the resolved location when one is known, the identity
     * otherwise.
     *
     * <p>Every fetch of this source goes through here, so that "which of the
     * two URLs" is answered in one place rather than at each reader.
     */
    public String effectiveUrl() {
        return StringUtils.isBlank(fetchUrl) ? url : fetchUrl;
    }
}
