package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.MissingSourcePolicy;
import de.mhus.hrafnagud.api.source.SourceListCreateRequest;
import de.mhus.hrafnagud.api.source.SourceListRefreshReport;
import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.api.source.SourceListUpdateRequest;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.ConflictException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.munin.source.SourceCandidate;
import de.mhus.hrafnagud.munin.source.SourceMergePolicy;
import de.mhus.hrafnagud.munin.source.SourceService;
import de.mhus.hrafnagud.munin.util.Slugs;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * Owns the {@code source_lists} collection and runs the refresh.
 *
 * <p>The refresh is the only place in the service where one subsystem
 * writes into another's data, and it does so strictly through
 * {@link SourceService} — the list never touches the {@code sources}
 * collection itself. That is what keeps the "a human edit outranks the
 * list" rule in one place instead of spread across both.
 */
@Service
@Slf4j
public class SourceListService {

    private static final String F_NAME = "name";
    private static final String F_ENABLED = "enabled";
    private static final String F_NEXT_REFRESH_AT = "nextRefreshAt";

    private final SourceListRepository repository;
    private final MongoTemplate mongoTemplate;
    private final SourceService sourceService;
    private final HttpFetcher fetcher;
    private final MuninProperties.SourceList config;
    private final MuninProperties.Feed feedConfig;
    private final Map<SourceListType, SourceListParser> parsers = new EnumMap<>(SourceListType.class);

    public SourceListService(SourceListRepository repository, MongoTemplate mongoTemplate,
            SourceService sourceService, HttpFetcher fetcher, List<SourceListParser> parserBeans,
            MuninProperties properties) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.sourceService = sourceService;
        this.fetcher = fetcher;
        this.config = properties.getSourceList();
        this.feedConfig = properties.getFeed();
        for (SourceListParser parser : parserBeans) {
            parsers.put(parser.type(), parser);
        }
    }

    // ─── Lookup ───

    public Optional<SourceListDocument> findByName(String name) {
        return repository.findByName(name);
    }

    public SourceListDocument requireByName(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new NotFoundException("source list", name));
    }

    public List<SourceListDocument> listAll() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, F_NAME));
    }

    public long countAll() {
        return repository.count();
    }

    // ─── Create / update / delete ───

    public SourceListDocument create(SourceListCreateRequest request, Instant now) {
        String url = UrlNormalizer.normalize(request.getUrl())
                .orElseThrow(() -> new BadRequestException(
                        "not a usable http(s) url: " + request.getUrl()));

        repository.findByUrl(url).ifPresent(existing -> {
            throw new ConflictException(
                    "list already registered as '" + existing.getName() + "'");
        });

        String name = StringUtils.isBlank(request.getName())
                ? Slugs.sourceName(url)
                : request.getName().trim();
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("source list name '" + name + "' is taken");
        }

        SourceListDocument document = SourceListDocument.builder()
                .name(name)
                .title(StringUtils.defaultIfBlank(request.getTitle(), Slugs.hostOf(url)))
                .type(request.getType() == null ? SourceListType.OPML : request.getType())
                .url(url)
                .enabled(request.getEnabled() == null || request.getEnabled())
                .defaultLanguage(StringUtils.trimToNull(request.getDefaultLanguage()))
                .defaultCountry(upperOrNull(request.getDefaultCountry()))
                .defaultCategories(clean(request.getDefaultCategories()))
                .defaultFetchIntervalSeconds(request.getDefaultFetchIntervalSeconds())
                .missingSourcePolicy(request.getMissingSourcePolicy() == null
                        ? MissingSourcePolicy.DISABLE
                        : request.getMissingSourcePolicy())
                .refreshIntervalSeconds(request.getRefreshIntervalSeconds() == null
                        ? config.getDefaultInterval().getSeconds()
                        : Math.max(60, request.getRefreshIntervalSeconds()))
                // Due immediately, so adding a list shows within one tick
                // whether it parses.
                .nextRefreshAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            return repository.save(document);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("source list was created concurrently: " + name);
        }
    }

    public SourceListDocument update(String name, SourceListUpdateRequest request, Instant now) {
        SourceListDocument list = requireByName(name);

        if (request.getTitle() != null) {
            list.setTitle(request.getTitle().trim());
        }
        if (request.getUrl() != null) {
            String url = UrlNormalizer.normalize(request.getUrl())
                    .orElseThrow(() -> new BadRequestException(
                            "not a usable http(s) url: " + request.getUrl()));
            repository.findByUrl(url)
                    .filter(other -> !other.getName().equals(name))
                    .ifPresent(other -> {
                        throw new ConflictException(
                                "list already registered as '" + other.getName() + "'");
                    });
            list.setUrl(url);
            list.setHttpEtag(null);
            list.setHttpLastModified(null);
        }
        if (request.getEnabled() != null) {
            list.setEnabled(request.getEnabled());
            if (request.getEnabled()) {
                list.setNextRefreshAt(now);
                list.setConsecutiveFailures(0);
            }
        }
        if (request.getDefaultLanguage() != null) {
            list.setDefaultLanguage(StringUtils.trimToNull(request.getDefaultLanguage()));
        }
        if (request.getDefaultCountry() != null) {
            list.setDefaultCountry(upperOrNull(request.getDefaultCountry()));
        }
        if (request.getDefaultCategories() != null) {
            list.setDefaultCategories(clean(request.getDefaultCategories()));
        }
        if (request.getDefaultFetchIntervalSeconds() != null) {
            list.setDefaultFetchIntervalSeconds(request.getDefaultFetchIntervalSeconds());
        }
        if (request.getMissingSourcePolicy() != null) {
            list.setMissingSourcePolicy(request.getMissingSourcePolicy());
        }
        if (request.getRefreshIntervalSeconds() != null) {
            list.setRefreshIntervalSeconds(Math.max(60, request.getRefreshIntervalSeconds()));
        }

        list.setUpdatedAt(now);
        return repository.save(list);
    }

    /**
     * Deletes the list. Sources it imported are kept and become orphans —
     * they keep collecting, and nothing then claims authority over them.
     * Removing them here would delete a chunk of the archive's provenance
     * as a side effect of a configuration change.
     */
    public void delete(String name) {
        SourceListDocument list = requireByName(name);
        repository.delete(list);
        log.info("Deleted source list {} — {} imported sources are now unmanaged",
                name, sourceService.findByList(name).size());
    }

    // ─── Refresh ───

    /** Claims up to {@code limit} lists that are due, leasing each. */
    public List<SourceListDocument> claimDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(config.getClaimLease());
        List<SourceListDocument> claimed = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Query query = Query.query(Criteria.where(F_ENABLED).is(true)
                            .and(F_NEXT_REFRESH_AT).lte(now))
                    .with(Sort.by(Sort.Direction.ASC, F_NEXT_REFRESH_AT));
            SourceListDocument list = mongoTemplate.findAndModify(query,
                    new Update().set(F_NEXT_REFRESH_AT, leaseUntil),
                    FindAndModifyOptions.options().returnNew(false),
                    SourceListDocument.class);
            if (list == null) {
                break;
            }
            claimed.add(list);
        }
        return claimed;
    }

    /** Refreshes by name — the manual endpoint's entry point. */
    public SourceListRefreshReport refresh(String name, Instant now) {
        return refresh(requireByName(name), now);
    }

    /**
     * Reads the list document and reconciles the registry against it.
     *
     * <p>A 304 short-circuits everything, including reconciliation. That is
     * not an optimisation but a correctness requirement: reconciliation
     * decides which sources the list has <em>dropped</em>, and a document we
     * did not read cannot support that conclusion. Treating "unchanged" as
     * "empty" would disable every source the list owns.
     */
    public SourceListRefreshReport refresh(SourceListDocument list, Instant now) {
        Instant startedAt = now;
        HttpFetchResult response = fetcher.get(list.getUrl(), list.getHttpEtag(),
                list.getHttpLastModified());

        if (response.isNotModified()) {
            SourceListRefreshReport report = SourceListRefreshReport.builder()
                    .outcome(FetchOutcome.NOT_MODIFIED)
                    .finishedAt(now)
                    .build();
            recordResult(list, FetchOutcome.NOT_MODIFIED, null, response, report, now);
            return report;
        }
        if (!response.isSuccess()) {
            String error = StringUtils.defaultIfBlank(response.getError(),
                    "HTTP " + response.getStatus());
            SourceListRefreshReport report = SourceListRefreshReport.builder()
                    .outcome(FetchOutcome.FETCH_ERROR)
                    .error(error)
                    .finishedAt(now)
                    .build();
            recordResult(list, FetchOutcome.FETCH_ERROR, error, response, report, now);
            return report;
        }

        SourceListParser parser = parsers.get(list.getType());
        if (parser == null) {
            throw new IllegalStateException("no parser registered for " + list.getType());
        }

        ParsedSourceList parsed;
        try {
            parsed = parser.parse(response.bodyAsText(), config.getMaxEntries());
        } catch (SourceListParseException e) {
            SourceListRefreshReport report = SourceListRefreshReport.builder()
                    .outcome(FetchOutcome.PARSE_ERROR)
                    .error(e.getMessage())
                    .finishedAt(now)
                    .build();
            recordResult(list, FetchOutcome.PARSE_ERROR, e.getMessage(), response, report, now);
            return report;
        }

        SourceMergePolicy.Defaults defaults = SourceMergePolicy.Defaults.builder()
                .language(list.getDefaultLanguage())
                .country(list.getDefaultCountry())
                .categories(list.getDefaultCategories())
                .build();
        long interval = list.getDefaultFetchIntervalSeconds() == null
                ? feedConfig.getDefaultInterval().getSeconds()
                : list.getDefaultFetchIntervalSeconds();

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        for (SourceCandidate candidate : parsed.getEntries()) {
            try {
                SourceService.MergeResult result = sourceService.mergeFromList(
                        list.getName(), candidate, defaults, interval, now);
                switch (result.outcome()) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException e) {
                // One bad entry must not abort a thousand good ones.
                skipped++;
                log.trace("Source list {}: entry {} failed: {}", list.getName(),
                        candidate.getUrl(), e.toString());
            }
        }

        int removed = sourceService.reconcileMissing(list.getName(), startedAt,
                list.getMissingSourcePolicy(), now);

        SourceListRefreshReport report = SourceListRefreshReport.builder()
                .outcome(FetchOutcome.OK)
                .entriesFound(parsed.getEntries().size())
                .created(created)
                .updated(updated)
                .unchanged(unchanged)
                .skipped(skipped)
                .removed(removed)
                .invalid(parsed.getInvalidCount())
                .warnings(parsed.getWarnings())
                .finishedAt(now)
                .build();

        recordResult(list, FetchOutcome.OK, null, response, report, now);
        log.info("Source list {}: {} entries → {} created, {} updated, {} unchanged,"
                        + " {} skipped, {} removed, {} invalid",
                list.getName(), parsed.getEntries().size(), created, updated, unchanged,
                skipped, removed, parsed.getInvalidCount());
        return report;
    }

    /**
     * Writes refresh state back with a conditional update rather than a
     * save — the document has been in hand across a network round trip and
     * an operator may have edited it meanwhile.
     */
    private void recordResult(SourceListDocument list, FetchOutcome outcome,
            @Nullable String error, HttpFetchResult response, SourceListRefreshReport report,
            Instant now) {

        int failures = outcome.successful() ? 0 : list.getConsecutiveFailures() + 1;
        long interval = list.getRefreshIntervalSeconds() <= 0
                ? config.getDefaultInterval().getSeconds()
                : list.getRefreshIntervalSeconds();
        // Directories change slowly; a failing one is retried on a doubling
        // delay but never more than a day out.
        long delay = outcome.successful()
                ? interval
                : Math.min(interval * (1L << Math.min(failures, 6)), 86_400L);

        Update update = new Update()
                .set("lastRefreshAt", now)
                .set("lastOutcome", outcome)
                .set("consecutiveFailures", failures)
                .set("lastReport", report)
                .set(F_NEXT_REFRESH_AT, now.plusSeconds(delay))
                .set("updatedAt", now);

        if (outcome.successful()) {
            update.unset("lastError");
            if (StringUtils.isNotBlank(response.getEtag())) {
                update.set("httpEtag", response.getEtag());
            }
            if (StringUtils.isNotBlank(response.getLastModified())) {
                update.set("httpLastModified", response.getLastModified());
            }
        } else {
            update.set("lastError", StringUtils.abbreviate(
                    StringUtils.defaultString(error, outcome.name()), 500));
        }

        mongoTemplate.updateFirst(Query.query(Criteria.where(F_NAME).is(list.getName())),
                update, SourceListDocument.class);
    }

    /** Makes a list due immediately — used by the manual refresh endpoint. */
    public void markDue(String name, Instant now) {
        mongoTemplate.updateFirst(Query.query(Criteria.where(F_NAME).is(name)),
                new Update().set(F_NEXT_REFRESH_AT, now), SourceListDocument.class);
    }

    private static @Nullable String upperOrNull(@Nullable String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private static List<String> clean(@Nullable List<String> values) {
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
