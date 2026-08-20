package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.category.CategoryMappingService;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import com.mongodb.client.result.UpdateResult;

/**
 * Owns the {@code articles} and {@code article_contents} collections.
 *
 * <p>The ingest path is the hot one and is written accordingly: one upsert
 * per feed entry, no read first, and <em>no write at all</em> in the
 * dominant case. A feed re-serves its entire window on every poll, so most
 * ingest calls concern an article the same source already delivered; those
 * resolve to an upsert that matches, adds nothing to the source set and
 * modifies nothing. The alternative — stamping a "still there" timestamp —
 * would make the archive's largest write load a field with no reader.
 *
 * <p>The upsert's return tells the three cases apart without a second
 * query: an upserted id means the article is new, a modified count means
 * this source is new to an article somebody else already had, and neither
 * means we have seen this exact pairing before.
 */
@Service
@Slf4j
public class ArticleService {

    private static final String F_DEDUP_KEY = "dedupKey";
    private static final String F_SOURCE_NAMES = "sourceNames";
    private static final String F_FIRST_SEEN_AT = "firstSeenAt";
    private static final String F_PUBLISHED_AT = "publishedAt";
    private static final String F_ID = "_id";
    private static final String F_LAST_SOURCE_ADDED_AT = "lastSourceAddedAt";
    private static final String F_LANGUAGE = "language";
    /** Text-index stemmer override — see TextIndexLanguage. */
    private static final String F_TEXT_LANGUAGE = "textLanguage";
    /**
     * How far past the requested count the body search reaches, so that
     * article-level filtering does not leave the page empty. Bounded on
     * purpose: an unbounded over-fetch to satisfy a filter is a full scan
     * with extra steps.
     */
    private static final int BODY_OVERFETCH = 4;

    private static final String F_PIVOT_TITLE = "pivotTitle";
    private static final String F_PIVOT_SUMMARY = "pivotSummary";
    private static final String F_CATEGORIES = "categories";
    private static final String F_CONTENT_STATUS = "contentStatus";
    private static final String F_CONTENT_NEXT_ATTEMPT_AT = "contentNextAttemptAt";
    private static final String F_CONTENT_ATTEMPTS = "contentAttempts";
    private static final String F_TRANSLATION_STATUS = "translationStatus";
    private static final String F_TRANSLATION_NEXT_ATTEMPT_AT = "translationNextAttemptAt";

    /** Languages reported by the statistics endpoint. */
    private static final int TOP_LANGUAGES = 20;

    private final ArticleRepository repository;
    private final ArticleContentRepository contentRepository;
    private final MongoTemplate mongoTemplate;
    private final EnrichmentService enrichmentService;
    private final MuninProperties.Content contentConfig;
    private final MuninProperties.Translation translationConfig;
    private final PlaceRegistry placeRegistry;
    private final CategoryMappingService categoryMappingService;

    public ArticleService(ArticleRepository repository, ArticleContentRepository contentRepository,
            EnrichmentService enrichmentService, MongoTemplate mongoTemplate,
            PlaceRegistry placeRegistry, CategoryMappingService categoryMappingService,
            MuninProperties properties) {
        this.repository = repository;
        this.contentRepository = contentRepository;
        this.enrichmentService = enrichmentService;
        this.mongoTemplate = mongoTemplate;
        this.placeRegistry = placeRegistry;
        this.categoryMappingService = categoryMappingService;
        this.contentConfig = properties.getContent();
        this.translationConfig = properties.getTranslation();
    }

    /** What one ingest call did. */
    public enum IngestOutcome {

        /** The article did not exist. */
        CREATED,

        /** It existed, delivered by other sources only; this source was added. */
        DUPLICATE_CROSS_SOURCE,

        /** This source had already delivered it. Nothing was written. */
        DUPLICATE_SAME_SOURCE
    }

    // ─── Ingest ───

    /**
     * Stores a feed entry, deduplicating against the whole archive.
     *
     * @param contentStatus initial body state; {@code PENDING} enqueues the
     *                      article for the content worker
     */
    public IngestOutcome ingest(ArticleCandidate candidate, SourceDocument source,
            LanguageResolver.Resolution language, ContentStatus contentStatus, Instant now) {

        ArticleDocument document = ArticleFactory.build(candidate, source, language,
                contentStatus, translationConfig.getPivotLanguage(),
                placeRegistry.pathForCountry(source.getCountry()),
                // Records every category as it goes past — that is how the
                // mapping table learns what exists — and returns the topics
                // already resolved. A category first seen here contributes
                // nothing to this article and everything to the next.
                categoryMappingService.recordAndResolve(candidate.getCategories(), now),
                now);

        try {
            return upsert(document, source.getName(), now);
        } catch (DuplicateKeyException e) {
            // Another worker inserted the same article between our upsert's
            // match and its insert. The article now exists, so the retry is
            // a plain update and cannot hit the same race again.
            log.trace("Article {} was inserted concurrently — retrying as update",
                    document.getUrl());
            return upsert(document, source.getName(), now);
        }
    }

    private IngestOutcome upsert(ArticleDocument document, String sourceName, Instant now) {
        Update update = new Update()
                .setOnInsert("url", document.getUrl())
                .setOnInsert("originalUrl", document.getOriginalUrl())
                .setOnInsert("contentHash", document.getContentHash())
                .setOnInsert("title", document.getTitle())
                .setOnInsert("summary", document.getSummary())
                .setOnInsert("author", document.getAuthor())
                .setOnInsert("imageUrl", document.getImageUrl())
                .setOnInsert("guid", document.getGuid())
                .setOnInsert(F_LANGUAGE, document.getLanguage())
                .setOnInsert(F_TEXT_LANGUAGE, document.getTextLanguage())
                .setOnInsert("languageSource", document.getLanguageSource())
                .setOnInsert(F_CATEGORIES, document.getCategories())
                .setOnInsert("topicIds", document.getTopicIds())
                // Origin belongs to the first source that delivered the
                // article, which is what setOnInsert means here: a second
                // publisher carrying the same story does not move where it
                // came from.
                .setOnInsert("originCountry", document.getOriginCountry())
                .setOnInsert("originPlaceIds", document.getOriginPlaceIds())
                .setOnInsert("firstSourceName", document.getFirstSourceName())
                .setOnInsert("publishedAt", document.getPublishedAt())
                .setOnInsert(F_FIRST_SEEN_AT, document.getFirstSeenAt())
                .setOnInsert(F_LAST_SOURCE_ADDED_AT, document.getLastSourceAddedAt())
                .setOnInsert(F_CONTENT_STATUS, document.getContentStatus())
                .setOnInsert(F_CONTENT_NEXT_ATTEMPT_AT, document.getContentNextAttemptAt())
                .setOnInsert(F_CONTENT_ATTEMPTS, 0)
                .setOnInsert("contentWordCount", 0)
                .setOnInsert(F_TRANSLATION_STATUS, document.getTranslationStatus())
                .setOnInsert(F_TRANSLATION_NEXT_ATTEMPT_AT, document.getTranslationNextAttemptAt())
                .setOnInsert("translationAttempts", 0)
                // Spring Data treats a null @Version as "not yet persisted"
                // and would turn a later save() into an insert. An upsert
                // does not populate it, so it is seeded here.
                .setOnInsert("version", 0L)
                .addToSet(F_SOURCE_NAMES, sourceName);

        UpdateResult result = mongoTemplate.upsert(
                Query.query(Criteria.where(F_DEDUP_KEY).is(document.getDedupKey())),
                update, ArticleDocument.class);

        if (result.getUpsertedId() != null) {
            return IngestOutcome.CREATED;
        }
        if (result.getModifiedCount() > 0) {
            // The set grew, so this source is new to an article we already
            // had. That is the moment the story spread, and the only repeat
            // delivery worth a timestamp.
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where(F_DEDUP_KEY).is(document.getDedupKey())),
                    new Update().set(F_LAST_SOURCE_ADDED_AT, now),
                    ArticleDocument.class);
            return IngestOutcome.DUPLICATE_CROSS_SOURCE;
        }
        return IngestOutcome.DUPLICATE_SAME_SOURCE;
    }

    // ─── Lookup and search ───

    public Optional<ArticleDocument> findById(String id) {
        return repository.findById(id);
    }

    public ArticleDocument requireById(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("article", id));
    }

    public Optional<ArticleDocument> findByUrl(String normalizedUrl) {
        return repository.findByDedupKey(ArticleFactory.dedupKey(normalizedUrl));
    }

    public Optional<ArticleContentDocument> findContent(String articleId) {
        return contentRepository.findByArticleId(articleId);
    }

    public List<ArticleDocument> search(ArticleQuery filter, int page, int size) {
        Query query = buildQuery(filter)
                .with(Sort.by(filter.isOldestFirst() ? Sort.Direction.ASC : Sort.Direction.DESC,
                        F_FIRST_SEEN_AT))
                .skip((long) page * size)
                .limit(size);
        return mongoTemplate.find(query, ArticleDocument.class);
    }

    /**
     * One page ordered by {@code publishedAt}, positioned by a cursor
     * rather than an offset.
     *
     * <p>Deliberately a different ordering from {@link #search}, which runs
     * on {@code firstSeenAt}. That one answers "what has this archive
     * collected lately" — the operator's question, and stable under late
     * arrivals. This one answers "what was published, in order" — the
     * question a reader merging several sources into one timeline asks, and
     * there the article's own timestamp is the only comparable key.
     *
     * <p>Articles without a {@code publishedAt} are excluded. There is no
     * defensible position for them in a chronological stream, and inventing
     * one from {@code firstSeenAt} would silently place a week-old article
     * at today's date.
     *
     * <p>The cursor is (timestamp, id) rather than the timestamp alone:
     * feeds routinely stamp a whole batch with the same minute, and a
     * timestamp-only cursor either repeats those rows or skips them.
     *
     * @param cursor    exclusive lower/upper bound; {@code null} starts at the end
     * @param ascending oldest-first, for a reader pulling forward in time
     * @return at most {@code limit} articles, ordered as requested
     */
    public List<ArticleDocument> pageByPublished(ArticleQuery filter,
            @Nullable ArticleCursor cursor, boolean ascending, int limit) {

        // Both extra conditions go in as arguments: the cursor's is keyless, and
        // so is the filter's own $and — adding it afterwards used to throw. See
        // buildQuery.
        Query query = buildQuery(filter,
                Criteria.where(F_PUBLISHED_AT).ne(null),
                beyond(cursor, ascending));
        Sort.Direction direction = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
        return mongoTemplate.find(
                query.with(Sort.by(direction, F_PUBLISHED_AT).and(Sort.by(direction, F_ID)))
                        .limit(limit),
                ArticleDocument.class);
    }

    /**
     * One page ordered by <em>relevance</em>, for a caller asking a question
     * rather than browsing a timeline.
     *
     * <p>The third ordering in this service, and each answers a different
     * question: {@link #search} is "what has this archive collected lately",
     * {@link #pageByPublished} is "what was published, in order", and this is
     * "what best matches these words". A search result sorted by date is not
     * a search result — the best match is rarely the newest document, and a
     * caller that gets one page has no way to look past it.
     *
     * <p>The index covers title and teaser in both the article's own language
     * and the pivot translation, so a query in either finds the article — see
     * {@link ArticleDocument#getPivotTitle()}.
     *
     * @param queryLanguage the language to stem the <em>query</em> with, from
     *     the caller's locale hint. Unknown or unsupported falls back to no
     *     stemming, which matches literally rather than wrongly.
     * <p>With {@code searchBodies} the extracted article text is searched too,
     * as a second tier below the metadata hits — see {@link #findByBodyText}
     * for why the two are concatenated rather than merged. The second query
     * only runs when the first did not fill the page, so the common case
     * stays one query.
     *
     * @param searchBodies also match the fetched article text. Costs a second
     *     query and only helps where bodies have been fetched at all.
     * @throws IllegalArgumentException if the query carries no text — without
     *     it there is no score to sort on, and the caller wants one of the
     *     other two methods
     */
    public List<ArticleDocument> searchByRelevance(ArticleQuery filter,
            @Nullable String queryLanguage, int limit, boolean searchBodies) {

        if (StringUtils.isBlank(filter.getText())) {
            throw new IllegalArgumentException(
                    "searchByRelevance needs query text; use search(...) to browse");
        }
        String stemmer = TextIndexLanguage.of(queryLanguage);
        TextCriteria text = (TextIndexLanguage.NONE.equals(stemmer)
                ? TextCriteria.forDefaultLanguage()
                : TextCriteria.forLanguage(stemmer))
                .matchingAny(filter.getText().trim());

        List<ArticleDocument> metadataHits = findByText(text, filter, limit);
        if (metadataHits.size() >= limit || !searchBodies) {
            return metadataHits;
        }

        // Second tier. Deliberately concatenated rather than merged: the two
        // scores come from two indexes over different fields and are not on a
        // comparable scale, so ranking them against each other would be
        // arithmetic on incomparable numbers. Ordering by tier instead is a
        // statement anyone can check — in news a headline match is a stronger
        // signal than a mention somewhere in the body — and within a tier the
        // scores are its own and do compare.
        //
        // The cost of the choice: an overwhelming body match ranks below a
        // weak headline match. Stated rather than hidden.
        Set<String> already = metadataHits.stream()
                .map(ArticleDocument::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ArticleDocument> bodyHits =
                findByBodyText(text, filter, already, limit - metadataHits.size());

        List<ArticleDocument> combined = new ArrayList<>(metadataHits);
        combined.addAll(bodyHits);
        return combined;
    }

    /** Title and teaser, in both the article's language and the pivot. */
    private List<ArticleDocument> findByText(TextCriteria text, ArticleQuery filter, int limit) {
        TextQuery query = new TextQuery(text).sortByScore();
        for (Criteria part : filterCriteria(filter)) {
            query.addCriteria(part);
        }
        return mongoTemplate.find(query.limit(limit), ArticleDocument.class);
    }

    /**
     * Articles whose <em>body</em> matches, minus the ones already found.
     *
     * <p>Two round trips, because the text lives in {@code article_contents}
     * while every filter is a property of the article. The content search is
     * over-fetched by {@link #BODY_OVERFETCH}× so that filtering does not
     * empty the page — bounded, because an unbounded over-fetch to satisfy a
     * filter is a full scan with extra steps.
     */
    private List<ArticleDocument> findByBodyText(TextCriteria text, ArticleQuery filter,
            Set<String> exclude, int limit) {

        TextQuery contentQuery = new TextQuery(text).sortByScore();
        // Over-fetched ids, NOT cut to `limit` here: the article-level filter runs
        // in the second query below, so truncating first would throw away the
        // candidates the over-fetch exists to provide. With a source filter and
        // ten wanted, the ten best body matches may all be from other sources
        // while the matching ones sit further down the list — cutting early
        // returned nothing and made BODY_OVERFETCH decoration.
        List<String> candidateIds = mongoTemplate
                .find(contentQuery.limit(limit * BODY_OVERFETCH), ArticleContentDocument.class)
                .stream()
                .map(ArticleContentDocument::getArticleId)
                .filter(id -> !exclude.contains(id))
                .toList();
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        List<Criteria> parts = new ArrayList<>(filterCriteria(filter));
        parts.add(Criteria.where("_id").in(candidateIds));
        List<ArticleDocument> found = mongoTemplate.find(
                new Query(new Criteria().andOperator(parts)), ArticleDocument.class);

        // Restore the order the body scores put them in — the second query
        // knows nothing about relevance and would hand them back in whatever
        // order the index walk produced.
        Map<String, ArticleDocument> byId = found.stream()
                .collect(Collectors.toMap(ArticleDocument::getId, a -> a, (a, b) -> a));
        // Cut to `limit` here, after filtering, so the page is filled with what
        // actually survived rather than with whatever the first `limit` ids were.
        return candidateIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .limit(limit)
                .toList();
    }

    /**
     * Counts matches.
     *
     * <p>Deliberately separate from {@link #search}: over a large archive an
     * unfiltered count is a full index scan, and paying it on every page
     * turn is how a listing endpoint becomes the slowest thing in the
     * service. The controller decides whether the number is worth it.
     */
    public long count(ArticleQuery filter) {
        return mongoTemplate.count(buildQuery(filter), ArticleDocument.class);
    }

    /**
     * The filter as a query, optionally with further conditions folded in.
     *
     * <p><b>Extra conditions are arguments, not later {@code addCriteria} calls},
     * and that is not a style preference.</b> {@code new Criteria().andOperator(…)}
     * produces a criteria whose key is {@code null}, and {@code Query} stores
     * criteria in a map keyed by exactly that — so a second keyless criteria
     * added afterwards throws {@code InvalidMongoDbApiUsageException} („you can't
     * add a second 'null' criteria"). It only shows up when both halves are
     * present, which is why a filtered first page worked and its follow-up page
     * did not. Everything keyless has to arrive here, before the {@code $and} is
     * built.
     *
     * <p>The text criteria is added separately on purpose: its key is
     * {@code $text}, so it cannot collide.
     */
    private Query buildQuery(ArticleQuery filter, Criteria... extra) {
        List<Criteria> parts = new ArrayList<>(filterCriteria(filter));
        for (Criteria criteria : extra) {
            if (criteria != null) {
                parts.add(criteria);
            }
        }
        Query query = parts.isEmpty()
                ? new Query()
                : new Query(new Criteria().andOperator(parts));
        if (StringUtils.isNotBlank(filter.getText())) {
            query.addCriteria(TextCriteria.forDefaultLanguage()
                    .matchingAny(filter.getText().trim()));
        }
        return query;
    }

    /**
     * „Strictly after the cursor", expressed so Mongo can still use the
     * {@code (publishedAt, _id)} compound index: beyond the timestamp, or equal
     * on it and beyond on the id. Null for no cursor.
     */
    private static @Nullable Criteria beyond(@Nullable ArticleCursor cursor, boolean ascending) {
        if (cursor == null) {
            return null;
        }
        return ascending
                ? new Criteria().orOperator(
                        Criteria.where(F_PUBLISHED_AT).gt(cursor.publishedAt()),
                        new Criteria().andOperator(
                                Criteria.where(F_PUBLISHED_AT).is(cursor.publishedAt()),
                                Criteria.where(F_ID).gt(cursor.articleId())))
                : new Criteria().orOperator(
                        Criteria.where(F_PUBLISHED_AT).lt(cursor.publishedAt()),
                        new Criteria().andOperator(
                                Criteria.where(F_PUBLISHED_AT).is(cursor.publishedAt()),
                                Criteria.where(F_ID).lt(cursor.articleId())));
    }

    /**
     * The filter half of a query, without ordering and without the text
     * match. Shared, because a relevance search applies the same filters as
     * a browse and only differs in how it sorts.
     */
    /** Non-blank, trimmed values of an optional filter list. */
    private static List<String> trimmed(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String v : raw) {
            if (StringUtils.isNotBlank(v)) {
                out.add(v.trim());
            }
        }
        return List.copyOf(out);
    }

    private List<Criteria> filterCriteria(ArticleQuery filter) {
        List<Criteria> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(filter.getSourceName())) {
            parts.add(Criteria.where(F_SOURCE_NAMES).is(filter.getSourceName()));
        }
        if (StringUtils.isNotBlank(filter.getLanguage())) {
            parts.add(Criteria.where(F_LANGUAGE).is(filter.getLanguage()));
        }
        if (StringUtils.isNotBlank(filter.getCategory())) {
            parts.add(Criteria.where(F_CATEGORIES).is(filter.getCategory()));
        }
        List<String> topics = trimmed(filter.getTopics());
        if (!topics.isEmpty()) {
            // Match against the materialised path, as with places: one
            // predicate serves a root topic and a leaf alike. Several values
            // are an "or" — $in over the same multikey index.
            parts.add(Criteria.where("topicIds").in(topics));
        }
        List<String> originPlaces = trimmed(filter.getOriginPlaces());
        if (!originPlaces.isEmpty()) {
            // Against a multikey array: the stored path holds every containing
            // place, so one predicate serves continent, region and country
            // alike, and several values are an "or".
            parts.add(Criteria.where("originPlaceIds").in(originPlaces));
        }
        if (filter.getContentStatus() != null) {
            parts.add(Criteria.where(F_CONTENT_STATUS).is(filter.getContentStatus()));
        }
        if (filter.getPublishedSince() != null || filter.getPublishedUntil() != null) {
            Criteria window = Criteria.where(F_PUBLISHED_AT);
            if (filter.getPublishedSince() != null) {
                window = window.gte(filter.getPublishedSince());
            }
            if (filter.getPublishedUntil() != null) {
                window = window.lt(filter.getPublishedUntil());
            }
            parts.add(window);
        }
        if (filter.getSince() != null || filter.getUntil() != null) {
            Criteria window = Criteria.where(F_FIRST_SEEN_AT);
            if (filter.getSince() != null) {
                window = window.gte(filter.getSince());
            }
            if (filter.getUntil() != null) {
                window = window.lt(filter.getUntil());
            }
            parts.add(window);
        }

        return parts;
    }

    // ─── Content queue ───

    /**
     * Atomically claims articles whose body is due to be fetched.
     *
     * <p>{@code contentNextAttemptAt} is both the retry schedule and the
     * lease: claiming pushes it out, so a worker that dies mid-fetch
     * releases the article when the lease expires. The attempt counter is
     * incremented at claim time rather than at completion, so an article
     * that reliably crashes the worker still exhausts its budget instead of
     * being retried forever.
     */
    public List<ArticleDocument> claimContentDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(contentConfig.getClaimLease());
        List<ArticleDocument> claimed = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Query query = Query.query(Criteria.where(F_CONTENT_STATUS).is(ContentStatus.PENDING)
                            .and(F_CONTENT_NEXT_ATTEMPT_AT).lte(now))
                    .with(Sort.by(Sort.Direction.ASC, F_CONTENT_NEXT_ATTEMPT_AT));
            ArticleDocument article = mongoTemplate.findAndModify(query,
                    new Update().set(F_CONTENT_NEXT_ATTEMPT_AT, leaseUntil)
                            .inc(F_CONTENT_ATTEMPTS, 1),
                    FindAndModifyOptions.options().returnNew(true),
                    ArticleDocument.class);
            if (article == null) {
                break;
            }
            claimed.add(article);
        }
        return claimed;
    }

    /** Stores a fetched body and marks the article {@code FETCHED}. */
    public void recordContentSuccess(String articleId, ArticleContentDocument content,
            Instant now) {

        content.setArticleId(articleId);
        content.setFetchedAt(now);
        // Set here rather than in the extractor: one choke point, and a
        // value MongoDB rejects would fail this write rather than being
        // caught somewhere it can be reasoned about.
        content.setTextLanguage(TextIndexLanguage.of(content.getLanguage()));
        ArticleContentDocument saved = contentRepository.findByArticleId(articleId)
                .map(existing -> {
                    content.setId(existing.getId());
                    return contentRepository.save(content);
                })
                .orElseGet(() -> contentRepository.save(content));

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(articleId)),
                new Update()
                        .set(F_CONTENT_STATUS, ContentStatus.FETCHED)
                        .set("contentId", saved.getId())
                        .set("contentFetchedAt", now)
                        .set("contentWordCount", saved.getWordCount())
                        .unset("contentError")
                        .unset(F_CONTENT_NEXT_ATTEMPT_AT),
                ArticleDocument.class);
    }

    /**
     * Records a failed body fetch.
     *
     * <p>A terminal status ends the attempt immediately. A retryable one
     * either schedules the next attempt on a doubling delay or, once the
     * budget is spent, becomes {@code FAILED}.
     */
    public void recordContentFailure(String articleId, ContentStatus status,
            @Nullable String error, int attempts, Instant now) {

        Update update = new Update()
                .set("contentError", StringUtils.abbreviate(
                        StringUtils.defaultString(error, status.name()), 500));

        boolean exhausted = attempts >= contentConfig.getMaxAttempts();
        if (status != ContentStatus.PENDING || exhausted) {
            update.set(F_CONTENT_STATUS,
                    status == ContentStatus.PENDING ? ContentStatus.FAILED : status);
            // Out of the queue: leaving an attempt time on a terminal
            // article would keep it in the partial index forever.
            update.unset(F_CONTENT_NEXT_ATTEMPT_AT);
        } else {
            long delaySeconds = contentConfig.getRetryDelay().getSeconds()
                    * (1L << Math.min(attempts, 6));
            update.set(F_CONTENT_NEXT_ATTEMPT_AT, now.plusSeconds(delaySeconds));
        }

        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(articleId)), update,
                ArticleDocument.class);
    }

    /**
     * Excludes an article from body fetching.
     *
     * <p>The only producer of {@link ContentStatus#SKIPPED}: ingest queues
     * everything, so taking one article out of the queue is an explicit act.
     * Useful against a source that reliably yields nothing extractable —
     * without it the only options are letting it burn its retry budget every
     * time or disabling the source entirely.
     */
    public void skipContent(String articleId, Instant now) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(articleId)),
                new Update()
                        .set(F_CONTENT_STATUS, ContentStatus.SKIPPED)
                        .set("contentError", "excluded from body fetching at " + now)
                        .unset(F_CONTENT_NEXT_ATTEMPT_AT),
                ArticleDocument.class);
    }

    /** Puts an article back in the content queue, resetting its budget. */
    public void requeueContent(String articleId, Instant now) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(articleId)),
                new Update()
                        .set(F_CONTENT_STATUS, ContentStatus.PENDING)
                        .set(F_CONTENT_NEXT_ATTEMPT_AT, now)
                        .set(F_CONTENT_ATTEMPTS, 0)
                        .unset("contentError"),
                ArticleDocument.class);
    }

    // ─── Translation queue ───

    /**
     * Atomically claims articles awaiting translation.
     *
     * <p>Same lease-in-the-schedule-field trick as the other two queues:
     * {@code translationNextAttemptAt} is pushed out on claim, so a
     * worker that dies mid-call releases the article when the lease
     * expires rather than pinning it.
     */
    public List<ArticleDocument> claimTranslationDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(translationConfig.getClaimLease());
        List<ArticleDocument> claimed = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Query query = Query.query(
                            Criteria.where(F_TRANSLATION_STATUS).is(TranslationStatus.PENDING)
                                    .and(F_TRANSLATION_NEXT_ATTEMPT_AT).lte(now))
                    .with(Sort.by(Sort.Direction.ASC, F_TRANSLATION_NEXT_ATTEMPT_AT));
            ArticleDocument article = mongoTemplate.findAndModify(query,
                    new Update().set(F_TRANSLATION_NEXT_ATTEMPT_AT, leaseUntil)
                            .inc("translationAttempts", 1),
                    FindAndModifyOptions.options().returnNew(true),
                    ArticleDocument.class);
            if (article == null) {
                break;
            }
            claimed.add(article);
        }
        return claimed;
    }

    /**
     * Marks an article translated. The translation itself is an
     * enrichment and was already written by the caller.
     */
    /**
     * Marks an article translated and mirrors the result into the searchable
     * pivot fields.
     *
     * <p>The translation itself lives in {@code enrichments}; these two
     * fields are a derived read model, written here and nowhere else. They
     * exist because MongoDB allows one text index per collection, so
     * searchable text has to sit on the document being searched — see
     * {@link ArticleDocument#getPivotTitle()}.
     *
     * <p>A re-run overwrites them, which is correct: the newest translation
     * is the one a reader is shown, so it is the one that should be findable.
     * The older run is not lost — it is still its own enrichment document.
     */
    public void recordTranslated(String articleId,
            @Nullable String pivotTitle, @Nullable String pivotSummary) {
        Update update = new Update()
                .set(F_TRANSLATION_STATUS, TranslationStatus.DONE)
                .unset(F_TRANSLATION_NEXT_ATTEMPT_AT)
                .unset("translationError");
        applyPivot(update, F_PIVOT_TITLE, pivotTitle);
        applyPivot(update, F_PIVOT_SUMMARY, pivotSummary);
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(articleId)), update,
                ArticleDocument.class);
    }

    /**
     * Blank means absent, not empty string: an empty entry in the text index
     * is noise, and a translation that produced no teaser should leave the
     * field unset rather than stamp a blank over a previous run's text.
     */
    private static void applyPivot(Update update, String field, @Nullable String value) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            update.unset(field);
        } else {
            update.set(field, trimmed);
        }
    }

    /**
     * Records a failed translation, retrying or giving up.
     *
     * <p>Giving up is a terminal {@code FAILED} rather than an endlessly
     * retried {@code PENDING}: a provider that is permanently
     * misconfigured would otherwise grow the backlog without bound, and
     * the status is what an operator reads to find out.
     */
    public void recordTranslationFailure(String articleId, @Nullable String error,
            int attempts, Instant now) {

        Update update = new Update().set("translationError",
                StringUtils.abbreviate(StringUtils.defaultString(error, "translation failed"), 500));

        if (attempts >= translationConfig.getMaxAttempts()) {
            update.set(F_TRANSLATION_STATUS, TranslationStatus.FAILED);
            update.unset(F_TRANSLATION_NEXT_ATTEMPT_AT);
        } else {
            long delaySeconds = translationConfig.getRetryDelay().getSeconds()
                    * (1L << Math.min(attempts, 6));
            update.set(F_TRANSLATION_NEXT_ATTEMPT_AT, now.plusSeconds(delaySeconds));
        }

        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(articleId)), update,
                ArticleDocument.class);
    }

    /** Queues an article for translation again, with a fresh budget. */
    public void requeueTranslation(String articleId, Instant now) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(articleId)),
                new Update()
                        .set(F_TRANSLATION_STATUS, TranslationStatus.PENDING)
                        .set(F_TRANSLATION_NEXT_ATTEMPT_AT, now)
                        .set("translationAttempts", 0)
                        .unset("translationError"),
                ArticleDocument.class);
    }

    /** Articles awaiting translation. */
    public long countTranslationBacklog() {
        return mongoTemplate.count(
                Query.query(Criteria.where(F_TRANSLATION_STATUS).is(TranslationStatus.PENDING)),
                ArticleDocument.class);
    }

    /** Article count per {@link TranslationStatus}. */
    public Map<String, Long> countByTranslationStatus() {
        return groupCount(F_TRANSLATION_STATUS, Integer.MAX_VALUE);
    }

    /** Deletes an article, its body, and everything derived from it. */
    public void delete(String articleId) {
        ArticleDocument article = requireById(articleId);
        contentRepository.deleteByArticleId(articleId);
        enrichmentService.deleteForArticle(articleId);
        repository.delete(article);
    }

    // ─── Statistics ───

    public long countAll() {
        return repository.count();
    }

    public long countSince(Instant since) {
        return mongoTemplate.count(
                Query.query(Criteria.where(F_FIRST_SEEN_AT).gte(since)), ArticleDocument.class);
    }

    /** Article count per {@link ContentStatus}. */
    public Map<String, Long> countByContentStatus() {
        return groupCount(F_CONTENT_STATUS, Integer.MAX_VALUE);
    }

    /** Article count per language, most frequent first. */
    public Map<String, Long> countByLanguage() {
        return groupCount(F_LANGUAGE, TOP_LANGUAGES);
    }

    private Map<String, Long> groupCount(String field, int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group(field).count().as("count"),
                Aggregation.sort(Sort.by(Sort.Direction.DESC, "count")),
                Aggregation.limit(limit));
        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, ArticleDocument.class, Document.class);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (Document row : results.getMappedResults()) {
            Object key = row.get("_id");
            Number count = row.get("count", Number.class);
            counts.put(key == null ? "unknown" : key.toString(),
                    count == null ? 0L : count.longValue());
        }
        return counts;
    }

    /** {@code firstSeenAt} of the newest article, or empty when the archive is. */
    public Optional<Instant> newestArticleAt() {
        return edgeTimestamp(Sort.Direction.DESC);
    }

    /** {@code firstSeenAt} of the oldest article. */
    public Optional<Instant> oldestArticleAt() {
        return edgeTimestamp(Sort.Direction.ASC);
    }

    private Optional<Instant> edgeTimestamp(Sort.Direction direction) {
        Query query = new Query().with(Sort.by(direction, F_FIRST_SEEN_AT)).limit(1);
        query.fields().include(F_FIRST_SEEN_AT);
        return Optional.ofNullable(mongoTemplate.findOne(query, ArticleDocument.class))
                .map(ArticleDocument::getFirstSeenAt);
    }
}
