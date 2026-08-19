package de.mhus.hrafnagud.munin.source;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.MissingSourcePolicy;
import de.mhus.hrafnagud.api.source.SourceCreateRequest;
import de.mhus.hrafnagud.api.source.SourceOrigin;
import de.mhus.hrafnagud.api.source.SourceType;
import de.mhus.hrafnagud.api.source.SourceUpdateRequest;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.ConflictException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import de.mhus.hrafnagud.munin.util.Slugs;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Owns the {@code sources} collection.
 *
 * <p>Two kinds of write live here and they use different mechanisms on
 * purpose. Read-modify-write operations driven by a caller (REST edits,
 * list merges) load the document, change it and save it under the
 * {@code @Version} guard. Operations driven by the ingest loop (claiming,
 * recording an outcome, bumping counters) never load the document at all —
 * they are conditional updates, so two workers racing on the same source
 * resolve at the database instead of overwriting each other's statistics.
 */
@Service
@Slf4j
public class SourceService {

    private static final String F_NAME = "name";
    private static final String F_URL = "url";
    private static final String F_ENABLED = "enabled";
    private static final String F_NEXT_FETCH_AT = "nextFetchAt";
    private static final String F_ORIGIN_LIST = "originListName";
    private static final String F_LAST_SEEN_IN_LIST = "lastSeenInListAt";
    private static final String F_CONSECUTIVE_FAILURES = "consecutiveFailures";
    private static final String F_ARTICLE_COUNT = "articleCount";
    private static final String F_LAST_ARTICLE_AT = "lastArticleAt";
    private static final String F_UPDATED_AT = "updatedAt";

    private final SourceRepository repository;
    private final MongoTemplate mongoTemplate;
    private final FetchSchedulePolicy schedulePolicy;

    public SourceService(SourceRepository repository, MongoTemplate mongoTemplate,
            MuninProperties properties) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.schedulePolicy = new FetchSchedulePolicy(properties.getFeed());
    }

    /** Interval classes this instance knows, for diagnostics and the API. */
    public java.util.Map<String, FetchProfile> fetchProfiles() {
        return schedulePolicy.profiles();
    }

    // ─── Lookup ───

    public Optional<SourceDocument> findByName(String name) {
        return repository.findByName(name);
    }

    public SourceDocument requireByName(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new NotFoundException("source", name));
    }

    public Optional<SourceDocument> findByUrl(String normalizedUrl) {
        return repository.findByUrl(normalizedUrl);
    }

    public List<SourceDocument> findByList(String listName) {
        return repository.findByOriginListName(listName);
    }

    /**
     * Paged listing with optional filters. {@code query} matches name, title
     * and URL as a case-insensitive substring — enough for an operator
     * hunting for one feed, and deliberately not a text index, since the
     * registry is small and a second text index would collide with the one
     * the article collection needs.
     */
    public List<SourceDocument> list(@Nullable Boolean enabled, @Nullable SourceType type,
            @Nullable String listName, @Nullable String query, @Nullable Boolean failing,
            int page, int size) {

        Query mongoQuery = new Query(listCriteria(enabled, type, listName, query, failing))
                .with(Sort.by(Sort.Direction.ASC, F_NAME))
                .skip((long) page * size)
                .limit(size);
        return mongoTemplate.find(mongoQuery, SourceDocument.class);
    }

    public long count(@Nullable Boolean enabled, @Nullable SourceType type,
            @Nullable String listName, @Nullable String query, @Nullable Boolean failing) {
        return mongoTemplate.count(
                new Query(listCriteria(enabled, type, listName, query, failing)),
                SourceDocument.class);
    }

    private Criteria listCriteria(@Nullable Boolean enabled, @Nullable SourceType type,
            @Nullable String listName, @Nullable String query, @Nullable Boolean failing) {

        List<Criteria> parts = new ArrayList<>();
        if (enabled != null) {
            parts.add(Criteria.where(F_ENABLED).is(enabled));
        }
        if (failing != null) {
            // Exactly the predicate countFailing() uses. Two definitions of
            // "failing" would let the stats page report a number that its own
            // "show me those" link cannot reproduce.
            parts.add(failing
                    ? Criteria.where(F_CONSECUTIVE_FAILURES).gt(0)
                    : Criteria.where(F_CONSECUTIVE_FAILURES).lte(0));
        }
        if (type != null) {
            parts.add(Criteria.where("type").is(type));
        }
        if (StringUtils.isNotBlank(listName)) {
            parts.add(Criteria.where(F_ORIGIN_LIST).is(listName));
        }
        if (StringUtils.isNotBlank(query)) {
            String escaped = java.util.regex.Pattern.quote(query.trim());
            parts.add(new Criteria().orOperator(
                    Criteria.where(F_NAME).regex(escaped, "i"),
                    Criteria.where("title").regex(escaped, "i"),
                    Criteria.where(F_URL).regex(escaped, "i")));
        }
        return parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts);
    }

    // ─── Create / update / delete ───

    /** Creates a manually configured source. */
    public SourceDocument create(SourceCreateRequest request, Instant now) {
        String url = UrlNormalizer.normalize(request.getUrl())
                .orElseThrow(() -> new BadRequestException(
                        "not a usable http(s) url: " + request.getUrl()));

        repository.findByUrl(url).ifPresent(existing -> {
            throw new ConflictException(
                    "feed already registered as source '" + existing.getName() + "'");
        });

        String name = StringUtils.isBlank(request.getName())
                ? Slugs.sourceName(url)
                : request.getName().trim();
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("source name '" + name + "' is taken");
        }

        long interval = schedulePolicy.initialIntervalSeconds(request.getFetchIntervalSeconds());
        SourceDocument document = SourceDocument.builder()
                .name(name)
                .title(StringUtils.defaultIfBlank(request.getTitle(), Slugs.hostOf(url)))
                .type(request.getType() == null ? SourceType.RSS : request.getType())
                .url(url)
                .siteUrl(normalizeOptionalUrl(request.getSiteUrl()))
                .enabled(request.getEnabled() == null || request.getEnabled())
                .language(StringUtils.trimToNull(request.getLanguage()))
                .country(normalizeCountry(request.getCountry()))
                .categories(cleanCategories(request.getCategories()))
                .origin(SourceOrigin.MANUAL)
                .lockedFields(new LinkedHashSet<>())
                .fetchIntervalSeconds(interval)
                // Due immediately: a source someone just added should show
                // whether it works within one tick, not in half an hour.
                .nextFetchAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            return repository.save(document);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("source was created concurrently: " + name);
        }
    }

    /**
     * Applies a sparse patch and locks every field it touched, so a later
     * refresh of the owning source list leaves the edit alone.
     */
    public SourceDocument update(String name, SourceUpdateRequest request, Instant now) {
        SourceDocument source = requireByName(name);
        Set<String> locked = new LinkedHashSet<>(source.getLockedFields());

        if (request.getTitle() != null) {
            source.setTitle(request.getTitle().trim());
            locked.add(SourceMergePolicy.FIELD_TITLE);
        }
        if (request.getUrl() != null) {
            String url = UrlNormalizer.normalize(request.getUrl())
                    .orElseThrow(() -> new BadRequestException(
                            "not a usable http(s) url: " + request.getUrl()));
            repository.findByUrl(url)
                    .filter(other -> !other.getName().equals(name))
                    .ifPresent(other -> {
                        throw new ConflictException(
                                "feed already registered as source '" + other.getName() + "'");
                    });
            source.setUrl(url);
            // The validators belong to the old URL and would produce a
            // spurious 304 against the new one.
            source.setHttpEtag(null);
            source.setHttpLastModified(null);
            locked.add(SourceMergePolicy.FIELD_URL);
        }
        if (request.getSiteUrl() != null) {
            source.setSiteUrl(StringUtils.trimToNull(normalizeOptionalUrl(request.getSiteUrl())));
            locked.add(SourceMergePolicy.FIELD_SITE_URL);
        }
        if (request.getEnabled() != null) {
            source.setEnabled(request.getEnabled());
            locked.add(SourceMergePolicy.FIELD_ENABLED);
            if (request.getEnabled()) {
                // Re-enabling should take effect now, not after whatever
                // backoff the source was sitting in when it was switched off.
                source.setNextFetchAt(now);
                source.setConsecutiveFailures(0);
            }
        }
        if (request.getLanguage() != null) {
            source.setLanguage(StringUtils.trimToNull(request.getLanguage()));
            locked.add(SourceMergePolicy.FIELD_LANGUAGE);
        }
        if (request.getCountry() != null) {
            source.setCountry(normalizeCountry(request.getCountry()));
            locked.add(SourceMergePolicy.FIELD_COUNTRY);
        }
        if (request.getCategories() != null) {
            source.setCategories(cleanCategories(request.getCategories()));
            locked.add(SourceMergePolicy.FIELD_CATEGORIES);
        }
        if (request.getFetchIntervalSeconds() != null) {
            source.setFetchIntervalSeconds(
                    schedulePolicy.clampToBounds(request.getFetchIntervalSeconds()));
            locked.add(SourceMergePolicy.FIELD_INTERVAL);
        }

        source.setLockedFields(locked);
        source.setUpdatedAt(now);
        return repository.save(source);
    }

    /**
     * Hands a source back to its list by clearing the locks. The next
     * refresh then re-applies the list's values.
     */
    public SourceDocument unlock(String name, Instant now) {
        SourceDocument source = requireByName(name);
        source.setLockedFields(new LinkedHashSet<>());
        source.setUpdatedAt(now);
        return repository.save(source);
    }

    public void delete(String name) {
        SourceDocument source = requireByName(name);
        repository.delete(source);
        log.info("Deleted source {} ({})", name, source.getUrl());
    }

    // ─── Source-list integration ───

    /**
     * Creates or updates the registry entry for one list entry.
     *
     * <p>A source that a human created, or that a <em>different</em> list
     * owns, is stamped as still-present but otherwise untouched: two lists
     * carrying the same feed must not fight over it, and the first one to
     * claim it keeps it.
     *
     * @return what happened, for the refresh report
     */
    public MergeResult mergeFromList(String listName, SourceCandidate candidate,
            SourceMergePolicy.Defaults defaults, @Nullable Long defaultIntervalSeconds,
            Instant now) {
        return mergeFromList(listName, candidate, defaults, defaultIntervalSeconds, null, now);
    }

    /**
     * The same, for a list that puts its sources in a named interval class.
     *
     * @param defaultIntervalSeconds interval the list asks for, or null to let
     *                               the profile decide. Null and "the global
     *                               default" are not the same thing: a blog
     *                               profile's own default is a day, and
     *                               substituting thirty minutes here only to
     *                               clamp it up to the profile's minimum would
     *                               land on six hours — a number nobody
     *                               configured anywhere.
     */
    public MergeResult mergeFromList(String listName, SourceCandidate candidate,
            SourceMergePolicy.Defaults defaults, @Nullable Long defaultIntervalSeconds,
            @Nullable String fetchProfile, Instant now) {

        Optional<SourceDocument> existing = repository.findByUrl(candidate.getUrl());
        if (existing.isEmpty()) {
            return new MergeResult(createFromList(listName, candidate, defaults,
                    defaultIntervalSeconds, fetchProfile, now), MergeOutcome.CREATED);
        }

        SourceDocument source = existing.get();
        boolean foreign = source.getOrigin() != SourceOrigin.LIST
                || !listName.equals(source.getOriginListName());
        if (foreign) {
            // Not ours to change, but seeing it still counts as presence for
            // the list that does own it — otherwise the reconciliation step
            // of that list would disable a feed this list also carries.
            touchSeenInList(source, now);
            return new MergeResult(source, MergeOutcome.SKIPPED);
        }

        Set<String> changed = SourceMergePolicy.apply(source, candidate, defaults);
        source.setLastSeenInListAt(now);
        if (!changed.isEmpty()) {
            source.setUpdatedAt(now);
        }
        SourceDocument saved = repository.save(source);
        return new MergeResult(saved,
                changed.isEmpty() ? MergeOutcome.UNCHANGED : MergeOutcome.UPDATED);
    }

    private SourceDocument createFromList(String listName, SourceCandidate candidate,
            SourceMergePolicy.Defaults defaults, @Nullable Long defaultIntervalSeconds,
            @Nullable String fetchProfile, Instant now) {

        FetchProfile profile = schedulePolicy.profile(fetchProfile);

        String name = uniqueName(Slugs.sourceName(candidate.getUrl()));
        SourceDocument document = SourceDocument.builder()
                .name(name)
                .title(StringUtils.defaultIfBlank(candidate.getTitle(),
                        Slugs.hostOf(candidate.getUrl())))
                .type(SourceType.RSS)
                .url(candidate.getUrl())
                .siteUrl(candidate.getSiteUrl())
                .enabled(true)
                .language(StringUtils.defaultIfBlank(candidate.getLanguage(),
                        defaults.getLanguage()))
                .country(StringUtils.defaultIfBlank(candidate.getCountry(),
                        defaults.getCountry()))
                .categories(SourceMergePolicy.mergeCategories(defaults.getCategories(),
                        candidate.getCategories()))
                .origin(SourceOrigin.LIST)
                .originListName(listName)
                .lockedFields(new LinkedHashSet<>())
                .lastSeenInListAt(now)
                .fetchProfile(fetchProfile)
                // The profile's own default and window, not the global ones: a
                // list that says "daily" must not be clamped back to the news
                // ceiling, and a list that says nothing must start where its
                // class starts.
                .fetchIntervalSeconds(
                        schedulePolicy.initialIntervalSeconds(profile, defaultIntervalSeconds))
                // Stagger imports: a thousand feeds arriving in one refresh
                // must not all become due in the same tick. The host limiter
                // would serialise them anyway, but only after the claim
                // query has already pulled them all into memory.
                .nextFetchAt(now.plusSeconds(spreadSeconds(candidate.getUrl())))
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return repository.save(document);
        } catch (DuplicateKeyException e) {
            // Another refresh created it between our lookup and our save.
            return repository.findByUrl(candidate.getUrl()).orElseThrow(() ->
                    new ConflictException("source vanished during concurrent import: " + name));
        }
    }

    /**
     * Deterministic 0..3599 second offset derived from the URL, so a
     * re-import of the same list produces the same spread rather than
     * reshuffling every source's due time.
     */
    private static long spreadSeconds(String url) {
        return Math.floorMod(url.hashCode(), 3600);
    }

    private String uniqueName(String candidate) {
        if (repository.findByName(candidate).isEmpty()) {
            return candidate;
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String attempt = candidate + "-" + suffix;
            if (repository.findByName(attempt).isEmpty()) {
                return attempt;
            }
        }
        throw new ConflictException("cannot derive a free source name from " + candidate);
    }

    private void touchSeenInList(SourceDocument source, Instant now) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_NAME).is(source.getName())),
                new Update().set(F_LAST_SEEN_IN_LIST, now),
                SourceDocument.class);
    }

    /**
     * Applies the list's policy to sources it imported but did not mention
     * in this refresh.
     *
     * @param refreshStartedAt sources whose {@code lastSeenInListAt} is
     *                         older than this were not in the document
     * @return number of sources disabled or deleted
     */
    public int reconcileMissing(String listName, Instant refreshStartedAt,
            MissingSourcePolicy policy, Instant now) {

        if (policy == MissingSourcePolicy.KEEP) {
            return 0;
        }
        Criteria criteria = Criteria.where(F_ORIGIN_LIST).is(listName)
                .orOperator(
                        Criteria.where(F_LAST_SEEN_IN_LIST).lt(refreshStartedAt),
                        Criteria.where(F_LAST_SEEN_IN_LIST).exists(false));

        if (policy == MissingSourcePolicy.DELETE) {
            long deleted = mongoTemplate.remove(new Query(criteria), SourceDocument.class)
                    .getDeletedCount();
            return (int) deleted;
        }

        // DISABLE, but never against an explicit human decision: a source
        // whose `enabled` is locked keeps whatever the operator set.
        Criteria disableCriteria = new Criteria().andOperator(
                criteria,
                Criteria.where(F_ENABLED).is(true),
                Criteria.where("lockedFields").ne(SourceMergePolicy.FIELD_ENABLED));
        long modified = mongoTemplate.updateMulti(new Query(disableCriteria),
                        new Update().set(F_ENABLED, false).set(F_UPDATED_AT, now),
                        SourceDocument.class)
                .getModifiedCount();
        return (int) modified;
    }

    /** Result of merging one list entry. */
    public record MergeResult(SourceDocument source, MergeOutcome outcome) {
    }

    /** What {@link #mergeFromList} did. */
    public enum MergeOutcome {
        CREATED, UPDATED, UNCHANGED, SKIPPED
    }

    // ─── Ingest loop ───

    /**
     * Atomically claims up to {@code limit} sources that are due.
     *
     * <p>Claiming pushes {@code nextFetchAt} out by the lease, which means
     * the field is both the schedule and the lock. One field rather than
     * two is what keeps the claim a single {@code findAndModify}: a
     * separate lease column would need a compound condition and a second
     * index, and would still leave the two able to disagree.
     *
     * <p>The returned documents carry their pre-claim state, which is what
     * the caller needs — in particular the validators for the conditional
     * request.
     */
    public List<SourceDocument> claimDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(schedulePolicy.claimLease());
        List<SourceDocument> claimed = new ArrayList<>();

        for (int i = 0; i < limit; i++) {
            Query query = Query.query(Criteria.where(F_ENABLED).is(true)
                            .and(F_NEXT_FETCH_AT).lte(now))
                    .with(Sort.by(Sort.Direction.ASC, F_NEXT_FETCH_AT));
            SourceDocument source = mongoTemplate.findAndModify(
                    query,
                    new Update().set(F_NEXT_FETCH_AT, leaseUntil),
                    FindAndModifyOptions.options().returnNew(false),
                    SourceDocument.class);
            if (source == null) {
                break;
            }
            claimed.add(source);
        }
        return claimed;
    }

    /**
     * Records the outcome of a poll and schedules the next one.
     *
     * <p>A conditional update rather than a save: the caller has been
     * holding this document for the duration of a network round trip, and
     * saving it whole would clobber anything an operator changed in the
     * meantime.
     *
     * @return seconds until the next poll
     */
    public long recordFetchResult(String name, FetchOutcome outcome, int newArticles,
            @Nullable String error, @Nullable String etag, @Nullable String lastModified,
            long currentIntervalSeconds, Instant now) {

        int failures = outcome.successful() ? 0 : currentFailures(name) + 1;
        long nextInterval = schedulePolicy.nextIntervalSeconds(
                schedulePolicy.profile(fetchProfileOf(name)),
                currentIntervalSeconds, outcome, newArticles, failures);

        Update update = new Update()
                .set("lastFetchAt", now)
                .set("lastOutcome", outcome)
                .set("fetchIntervalSeconds", nextInterval)
                .set(F_NEXT_FETCH_AT, now.plusSeconds(nextInterval))
                .set(F_CONSECUTIVE_FAILURES, failures)
                .set(F_UPDATED_AT, now);

        if (outcome.successful()) {
            update.unset("lastError");
            // Only replace validators we actually received: a 304 carries
            // no ETag, and dropping the stored one would turn every
            // subsequent poll back into a full download.
            if (StringUtils.isNotBlank(etag)) {
                update.set("httpEtag", etag);
            }
            if (StringUtils.isNotBlank(lastModified)) {
                update.set("httpLastModified", lastModified);
            }
        } else {
            update.set("lastError", StringUtils.abbreviate(
                    StringUtils.defaultString(error, outcome.name()), 500));
        }

        mongoTemplate.updateFirst(Query.query(Criteria.where(F_NAME).is(name)), update,
                SourceDocument.class);
        return nextInterval;
    }

    /**
     * The source's interval class, read on the way to rescheduling it.
     *
     * <p>One projected field rather than threading the name through the ingest
     * path: the caller already holds the document, but the update path here is
     * deliberately load-free, and adding a parameter to keep it that way would
     * push the profile into two more signatures for one string.
     */
    private @Nullable String fetchProfileOf(String name) {
        Query query = Query.query(Criteria.where(F_NAME).is(name));
        query.fields().include("fetchProfile");
        SourceDocument source = mongoTemplate.findOne(query, SourceDocument.class);
        return source == null ? null : source.getFetchProfile();
    }

    private int currentFailures(String name) {
        Query query = Query.query(Criteria.where(F_NAME).is(name));
        query.fields().include(F_CONSECUTIVE_FAILURES);
        SourceDocument source = mongoTemplate.findOne(query, SourceDocument.class);
        return source == null ? 0 : source.getConsecutiveFailures();
    }

    /**
     * Adds to a source's article counters. Atomic {@code $inc} because
     * several sources can deliver into the same tick and a read-modify-write
     * would lose increments.
     */
    public void recordArticles(String name, long newArticles, Instant lastArticleAt) {
        if (newArticles <= 0) {
            return;
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_NAME).is(name)),
                new Update().inc(F_ARTICLE_COUNT, newArticles).set(F_LAST_ARTICLE_AT, lastArticleAt),
                SourceDocument.class);
    }

    /** Makes a source due immediately — used by the manual fetch endpoint. */
    public void markDue(String name, Instant now) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where(F_NAME).is(name)),
                new Update().set(F_NEXT_FETCH_AT, now),
                SourceDocument.class);
    }

    // ─── Statistics ───

    public long countAll() {
        return repository.count();
    }

    public long countEnabled() {
        return repository.countByEnabled(true);
    }

    public long countFailing() {
        return mongoTemplate.count(
                Query.query(Criteria.where(F_CONSECUTIVE_FAILURES).gt(0)), SourceDocument.class);
    }

    /** Lease length, exposed so the ingest loop can size its own timeouts. */
    public Duration claimLease() {
        return schedulePolicy.claimLease();
    }

    // ─── Normalisation helpers ───

    private static @Nullable String normalizeOptionalUrl(@Nullable String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return UrlNormalizer.normalizeOrRaw(value);
    }

    private static @Nullable String normalizeCountry(@Nullable String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private static List<String> cleanCategories(@Nullable List<String> values) {
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
