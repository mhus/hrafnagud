package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.catalog.CatalogCreateRequest;
import de.mhus.hrafnagud.api.catalog.CatalogRefreshReport;
import de.mhus.hrafnagud.api.catalog.CatalogUpdateRequest;
import de.mhus.hrafnagud.api.catalog.MissingListPolicy;
import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.ConflictException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import de.mhus.hrafnagud.munin.settings.MuninSettings;
import de.mhus.hrafnagud.munin.sourcelist.SourceListCandidate;
import de.mhus.hrafnagud.munin.sourcelist.SourceListService;
import de.mhus.hrafnagud.munin.util.Slugs;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code source_catalogs} collection and runs the refresh.
 *
 * <p>Third layer, same shape as the two below it: read the directory,
 * reconcile what it offers against what we have, record the result, schedule
 * the next pass. It writes into the list layer strictly through
 * {@link SourceListService}, exactly as that one writes into the source layer
 * through {@code SourceService} — so "who may overwrite whose configuration"
 * stays answered in one place per layer.
 */
@Service
@Slf4j
public class SourceCatalogService {

    private static final String F_NAME = "name";
    private static final String F_ENABLED = "enabled";
    private static final String F_NEXT_REFRESH_AT = "nextRefreshAt";

    private final SourceCatalogRepository repository;
    private final MongoTemplate mongoTemplate;
    private final SourceListService listService;
    private final MuninSettings.Catalog config;
    private final MuninSettings.SourceList listConfig;
    private final Map<String, CatalogReader> readers = new LinkedHashMap<>();

    public SourceCatalogService(SourceCatalogRepository repository, MongoTemplate mongoTemplate,
            SourceListService listService, List<CatalogReader> readerBeans,
            MuninSettings settings) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.listService = listService;
        this.config = settings.getCatalog();
        this.listConfig = settings.getSourceList();
        for (CatalogReader reader : readerBeans) {
            readers.put(reader.type(), reader);
        }
    }

    // ─── Lookup ───

    public Optional<SourceCatalogDocument> findByName(String name) {
        return repository.findByName(name);
    }

    public SourceCatalogDocument requireByName(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new NotFoundException("catalog", name));
    }

    public List<SourceCatalogDocument> listAll() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, F_NAME));
    }

    public long countAll() {
        return repository.count();
    }

    /** Reader ids that exist in this build, for the API's error messages and the UI. */
    public Map<String, String> availableReaders() {
        Map<String, String> byType = new LinkedHashMap<>();
        readers.forEach((type, reader) -> byType.put(type, reader.displayName()));
        return byType;
    }

    // ─── Create / update / delete ───

    public SourceCatalogDocument create(CatalogCreateRequest request, Instant now) {
        String type = StringUtils.trimToEmpty(request.getType());
        if (!readers.containsKey(type)) {
            throw new BadRequestException("no reader for catalog type '" + type
                    + "' — known types: " + String.join(", ", readers.keySet()));
        }
        String url = UrlNormalizer.normalize(request.getUrl())
                .orElseThrow(() -> new BadRequestException(
                        "not a usable http(s) url: " + request.getUrl()));

        // A warning, not a refusal: registering the same directory twice with
        // different filters is how a collection that mixes kinds — news lists
        // and blog lists in one repository — gets one catalogue per kind, each
        // with its own fetch profile. An accidental duplicate still shows up
        // in the log.
        repository.findByUrl(url).ifPresent(existing -> log.info(
                "Catalog url {} is already registered as '{}'; registering a second one — "
                        + "check the include filters do not overlap",
                url, existing.getName()));

        String name = StringUtils.isBlank(request.getName())
                ? Slugs.sourceName(url)
                : request.getName().trim();
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("catalog name '" + name + "' is taken");
        }

        SourceCatalogDocument document = SourceCatalogDocument.builder()
                .name(name)
                .title(StringUtils.defaultIfBlank(request.getTitle(), Slugs.hostOf(url)))
                .type(type)
                .url(url)
                .params(clean(request.getParams()))
                // Off unless asked for. A catalogue is a standing instruction
                // to crawl somebody else's list of publishers, and several
                // ship with the application: a fresh installation that starts
                // all of them at once is a surprise, not a feature. Turning
                // one on is a click in the console.
                .enabled(request.getEnabled() != null && request.getEnabled())
                .include(cleanList(request.getInclude()))
                .exclude(cleanList(request.getExclude()))
                .listRefreshIntervalSeconds(request.getListRefreshIntervalSeconds())
                .fetchProfile(StringUtils.trimToNull(request.getFetchProfile()))
                .sourceFetchIntervalSeconds(request.getSourceFetchIntervalSeconds())
                .missingListPolicy(request.getMissingListPolicy() == null
                        ? MissingListPolicy.DISABLE
                        : request.getMissingListPolicy())
                .refreshIntervalSeconds(request.getRefreshIntervalSeconds() == null
                        ? config.defaultInterval().value().getSeconds()
                        : Math.max(300, request.getRefreshIntervalSeconds()))
                // Due immediately, so registering a catalogue shows within one
                // tick whether it resolves at all.
                .nextRefreshAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            return repository.save(document);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("catalog was created concurrently: " + name);
        }
    }

    public SourceCatalogDocument update(String name, CatalogUpdateRequest request, Instant now) {
        SourceCatalogDocument catalog = requireByName(name);

        if (request.getTitle() != null) {
            catalog.setTitle(request.getTitle().trim());
        }
        if (request.getUrl() != null) {
            String url = UrlNormalizer.normalize(request.getUrl())
                    .orElseThrow(() -> new BadRequestException(
                            "not a usable http(s) url: " + request.getUrl()));
            repository.findByUrl(url)
                    .filter(other -> !other.getName().equals(name))
                    .ifPresent(other -> log.info(
                            "Catalog url {} is also registered as '{}'", url, other.getName()));
            catalog.setUrl(url);
            catalog.setFingerprint(null);
        }
        if (request.getParams() != null) {
            catalog.setParams(clean(request.getParams()));
            catalog.setFingerprint(null);
        }
        if (request.getEnabled() != null) {
            catalog.setEnabled(request.getEnabled());
            if (request.getEnabled()) {
                catalog.setNextRefreshAt(now);
                catalog.setConsecutiveFailures(0);
            }
        }
        if (request.getInclude() != null) {
            catalog.setInclude(cleanList(request.getInclude()));
            // A widened filter must take effect on the next pass, and the
            // fingerprint would otherwise report "nothing changed" — the
            // directory did not change, but what we want from it did.
            catalog.setFingerprint(null);
        }
        if (request.getExclude() != null) {
            catalog.setExclude(cleanList(request.getExclude()));
            catalog.setFingerprint(null);
        }
        if (request.getListRefreshIntervalSeconds() != null) {
            catalog.setListRefreshIntervalSeconds(request.getListRefreshIntervalSeconds());
        }
        if (request.getFetchProfile() != null) {
            // Blank clears it back to the default profile; a name that does
            // not exist is accepted and warned about at poll time, because a
            // profile may be configured after the catalogue that uses it.
            catalog.setFetchProfile(StringUtils.trimToNull(request.getFetchProfile()));
        }
        if (request.getSourceFetchIntervalSeconds() != null) {
            catalog.setSourceFetchIntervalSeconds(request.getSourceFetchIntervalSeconds());
        }
        if (request.getMissingListPolicy() != null) {
            catalog.setMissingListPolicy(request.getMissingListPolicy());
        }
        if (request.getRefreshIntervalSeconds() != null) {
            catalog.setRefreshIntervalSeconds(
                    Math.max(300, request.getRefreshIntervalSeconds()));
        }

        catalog.setUpdatedAt(now);
        return repository.save(catalog);
    }

    /**
     * Deletes the catalogue. Lists it imported are kept and become unmanaged,
     * for the same reason a deleted list keeps its sources: a configuration
     * change must not take a chunk of the archive with it.
     */
    public void delete(String name) {
        SourceCatalogDocument catalog = requireByName(name);
        repository.delete(catalog);
        log.info("Deleted catalog {} — {} imported source lists are now unmanaged",
                name, listService.countByCatalog(name));
    }

    // ─── Refresh ───

    /** Claims up to {@code limit} catalogues that are due, leasing each. */
    public List<SourceCatalogDocument> claimDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(config.claimLease().value());
        List<SourceCatalogDocument> claimed = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Query query = Query.query(Criteria.where(F_ENABLED).is(true)
                            .and(F_NEXT_REFRESH_AT).lte(now))
                    .with(Sort.by(Sort.Direction.ASC, F_NEXT_REFRESH_AT));
            SourceCatalogDocument catalog = mongoTemplate.findAndModify(query,
                    new Update().set(F_NEXT_REFRESH_AT, leaseUntil),
                    FindAndModifyOptions.options().returnNew(false),
                    SourceCatalogDocument.class);
            if (catalog == null) {
                break;
            }
            claimed.add(catalog);
        }
        return claimed;
    }

    /**
     * Refreshes by name — the manual endpoint's entry point.
     *
     * <p>Runs whether or not the catalogue is enabled: the flag governs the
     * schedule, and somebody asking for a refresh has already decided.
     */
    public CatalogRefreshReport refresh(String name, Instant now) {
        return refresh(requireByName(name), now);
    }

    /**
     * Resolves the catalogue and reconciles the list registry against it.
     *
     * <p>An unchanged fingerprint short-circuits everything, reconciliation
     * included — the same correctness argument as the 304 one layer down: a
     * directory we did not act on cannot support the conclusion that it has
     * dropped an entry.
     */
    public CatalogRefreshReport refresh(SourceCatalogDocument catalog, Instant now) {
        Instant startedAt = now;

        CatalogReader reader = readers.get(catalog.getType());
        if (reader == null) {
            String error = "no reader for catalog type '" + catalog.getType() + "'";
            return fail(catalog, FetchOutcome.PARSE_ERROR, error, now);
        }

        CatalogReadResult read;
        try {
            read = reader.read(catalog);
        } catch (CatalogReadException e) {
            return fail(catalog, FetchOutcome.FETCH_ERROR, e.getMessage(), now);
        } catch (RuntimeException e) {
            // A reader is third-party-ish code by design; a bug in one must
            // fail its catalogue, not the tick that runs every catalogue.
            log.warn("Catalog reader {} threw", catalog.getType(), e);
            return fail(catalog, FetchOutcome.FETCH_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), now);
        }

        String fingerprint = read.fingerprint();
        if (fingerprint.equals(catalog.getFingerprint())) {
            CatalogRefreshReport report = CatalogRefreshReport.builder()
                    .outcome(FetchOutcome.NOT_MODIFIED)
                    .entriesFound(read.entries().size())
                    .finishedAt(now)
                    .build();
            record(catalog, FetchOutcome.NOT_MODIFIED, null, fingerprint, report, now);
            return report;
        }

        CatalogEntryFilter filter = CatalogEntryFilter.of(catalog);
        long listInterval = catalog.getListRefreshIntervalSeconds() == null
                ? listConfig.defaultInterval().value().getSeconds()
                : catalog.getListRefreshIntervalSeconds();

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        int selected = 0;
        for (CatalogEntry entry : read.entries()) {
            if (!filter.accepts(entry.key())) {
                continue;
            }
            selected++;
            try {
                SourceListService.MergeResult result = listService.mergeFromCatalog(
                        catalog.getName(), candidateOf(entry, catalog), listInterval, now);
                switch (result.outcome()) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException e) {
                // One bad entry must not abort the rest.
                skipped++;
                log.trace("Catalog {}: entry {} failed: {}", catalog.getName(),
                        entry.key(), e.toString());
            }
        }

        int removed = listService.reconcileMissingFromCatalog(
                catalog.getName(), startedAt, catalog.getMissingListPolicy(), now);

        CatalogRefreshReport report = CatalogRefreshReport.builder()
                .outcome(FetchOutcome.OK)
                .entriesFound(read.entries().size())
                .entriesSelected(selected)
                .created(created)
                .updated(updated)
                .unchanged(unchanged)
                .skipped(skipped)
                .removed(removed)
                .invalid(read.invalid())
                .warnings(read.warnings())
                .finishedAt(now)
                .build();

        record(catalog, FetchOutcome.OK, null, fingerprint, report, now);
        log.info("Catalog {}: {} entries, {} selected → {} created, {} updated, {} unchanged,"
                        + " {} skipped, {} removed",
                catalog.getName(), read.entries().size(), selected, created, updated,
                unchanged, skipped, removed);
        return report;
    }

    private CatalogRefreshReport fail(SourceCatalogDocument catalog, FetchOutcome outcome,
            @Nullable String error, Instant now) {
        CatalogRefreshReport report = CatalogRefreshReport.builder()
                .outcome(outcome)
                .error(error)
                .finishedAt(now)
                .build();
        record(catalog, outcome, error, null, report, now);
        return report;
    }

    /**
     * Writes refresh state back with a conditional update rather than a save —
     * the document has been in hand across a network round trip and an
     * operator may have edited it meanwhile.
     */
    private void record(SourceCatalogDocument catalog, FetchOutcome outcome,
            @Nullable String error, @Nullable String fingerprint,
            CatalogRefreshReport report, Instant now) {

        int failures = outcome.successful() ? 0 : catalog.getConsecutiveFailures() + 1;
        long interval = catalog.getRefreshIntervalSeconds() <= 0
                ? config.defaultInterval().value().getSeconds()
                : catalog.getRefreshIntervalSeconds();
        // Directories change slowly and a failing one is usually failing for
        // everybody; back off hard, but never past a week.
        long delay = outcome.successful()
                ? interval
                : Math.min(interval * (1L << Math.min(failures, 6)), 604_800L);

        Update update = new Update()
                .set("lastRefreshAt", now)
                .set("lastOutcome", outcome)
                .set("consecutiveFailures", failures)
                .set("lastReport", report)
                .set(F_NEXT_REFRESH_AT, now.plusSeconds(delay))
                .set("updatedAt", now);

        if (outcome.successful()) {
            update.unset("lastError");
            if (fingerprint != null) {
                update.set("fingerprint", fingerprint);
            }
        } else {
            update.set("lastError", StringUtils.abbreviate(
                    StringUtils.defaultString(error, outcome.name()), 500));
        }

        mongoTemplate.updateFirst(Query.query(Criteria.where(F_NAME).is(catalog.getName())),
                update, SourceCatalogDocument.class);
    }

    private static SourceListCandidate candidateOf(CatalogEntry entry,
            SourceCatalogDocument catalog) {
        return new SourceListCandidate(entry.url(), entry.title(), entry.type(),
                entry.country(), entry.categories(), null,
                catalog.getFetchProfile(), catalog.getSourceFetchIntervalSeconds());
    }

    private static Map<String, String> clean(@Nullable Map<String, String> params) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (params == null) {
            return cleaned;
        }
        for (Map.Entry<String, String> entry : new HashMap<>(params).entrySet()) {
            if (StringUtils.isNotBlank(entry.getKey()) && entry.getValue() != null) {
                cleaned.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        return cleaned;
    }

    private static List<String> cleanList(@Nullable List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                unique.add(value.trim());
            }
        }
        return new ArrayList<>(unique);
    }
}
