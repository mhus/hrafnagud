package de.mhus.hrafnagud.munin.category;

import de.mhus.hrafnagud.api.category.CategoryMappingStatus;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
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
 * Owns the {@code category_mappings} collection.
 *
 * <p>Two things happen here. At ingest, every category an article carries is
 * <b>recorded</b> — created if new, counted if known — and stage one is run on
 * it immediately, because it is pure string work and the frequent categories
 * are exactly the ones worth resolving before the article is stored. Later, the
 * resolution tick claims what stage one could not settle and hands it to a
 * model.
 *
 * <p>The article's own {@code categories} field is never touched; this service
 * only ever answers "what do these strings mean", and the caller writes the
 * derived topic ids. See specs/categories.md §6.
 */
@Service
@Slf4j
public class CategoryMappingService {

    private static final String F_KEY = "key";
    private static final String F_STATUS = "status";
    private static final String F_NEXT_ATTEMPT_AT = "nextAttemptAt";

    private final CategoryMappingRepository repository;
    private final MongoTemplate mongoTemplate;
    private final CategoryMatcher matcher;
    private final TopicRegistry topics;
    private final MuninProperties.Category config;

    public CategoryMappingService(CategoryMappingRepository repository,
            MongoTemplate mongoTemplate, CategoryMatcher matcher, TopicRegistry topics,
            MuninProperties properties) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.matcher = matcher;
        this.topics = topics;
        this.config = properties.getCategory();
    }

    // ─── Lookup ───

    public Optional<CategoryMappingDocument> findByKey(String key) {
        return repository.findByKey(CategoryKeys.normalise(key));
    }

    public CategoryMappingDocument requireByKey(String key) {
        return findByKey(key)
                .orElseThrow(() -> new NotFoundException("category mapping", key));
    }

    public List<CategoryMappingDocument> list(@Nullable CategoryMappingStatus status,
            int page, int size) {

        Query query = status == null ? new Query()
                : Query.query(Criteria.where(F_STATUS).is(status));
        // Most used first: a list of mappings is read to fix the ones that
        // matter, and alphabetical order buries them under one-off tags.
        query.with(Sort.by(Sort.Direction.DESC, "useCount"))
                .skip((long) Math.max(page, 0) * size)
                .limit(size);
        return mongoTemplate.find(query, CategoryMappingDocument.class);
    }

    public long count(@Nullable CategoryMappingStatus status) {
        return status == null
                ? repository.count()
                : mongoTemplate.count(Query.query(Criteria.where(F_STATUS).is(status)),
                        CategoryMappingDocument.class);
    }

    // ─── Ingest ───

    /**
     * Records the categories of one article and answers with the topic path
     * everything already resolved implies.
     *
     * <p>Called once per stored article, so it must stay cheap: one upsert per
     * category, no reads of the vocabulary beyond the in-memory indexes, and
     * stage one only for categories that are new.
     */
    public List<String> recordAndResolve(Collection<String> categories, Instant now) {
        Set<String> topicIds = new LinkedHashSet<>();
        for (String raw : categories) {
            CategoryMappingDocument mapping = record(raw, now);
            if (mapping != null && mapping.getStatus().resolved()) {
                topicIds.addAll(mapping.getTopicPath());
            }
        }
        return new ArrayList<>(topicIds);
    }

    /** Creates or counts one category. Returns null for a string that normalises to nothing. */
    private @Nullable CategoryMappingDocument record(String raw, Instant now) {
        String key = CategoryKeys.normalise(raw);
        if (key.isEmpty()) {
            return null;
        }

        Optional<CategoryMappingDocument> existing = repository.findByKey(key);
        if (existing.isPresent()) {
            // A conditional update rather than a save: this runs on every
            // article of every source, and two workers counting the same
            // category must not overwrite each other's totals.
            mongoTemplate.updateFirst(Query.query(Criteria.where(F_KEY).is(key)),
                    new Update().inc("useCount", 1).set("lastSeenAt", now),
                    CategoryMappingDocument.class);
            return existing.get();
        }
        return create(key, raw, now);
    }

    private CategoryMappingDocument create(String key, String raw, Instant now) {
        Optional<CategoryMatcher.Match> match = matcher.match(raw);

        CategoryMappingStatus status = CategoryMappingStatus.NEW;
        String topicId = null;
        List<String> path = List.of();
        double confidence = 0;
        String decidedBy = null;

        if (match.isPresent()) {
            CategoryMatcher.Match found = match.get();
            confidence = found.confidence();
            decidedBy = found.rule().name();
            topicId = found.topic().id();
            path = found.topic().path();
            // Above the threshold it counts as resolved; below it is a guess
            // that stage two still has to settle. The single-word rule sits
            // deliberately below — see CategoryMatcher.
            status = confidence >= config.getAcceptConfidence()
                    ? CategoryMappingStatus.RESOLVED
                    : CategoryMappingStatus.GUESSED;
        }

        CategoryMappingDocument document = CategoryMappingDocument.builder()
                .key(key)
                .raw(raw.trim())
                .status(status)
                .topicId(topicId)
                .topicPath(new ArrayList<>(path))
                .confidence(confidence)
                .decidedBy(decidedBy)
                // Pending entries are due at once: the queue is small and a
                // category nobody resolved is a gap in every article carrying
                // it.
                .nextAttemptAt(status.pending() ? now : null)
                .useCount(1)
                .lastSeenAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return repository.save(document);
        } catch (DuplicateKeyException e) {
            // Another worker created it between our lookup and our save.
            return repository.findByKey(key).orElse(document);
        }
    }

    // ─── Stage two ───

    /** Claims up to {@code limit} pending mappings, leasing each. */
    public List<CategoryMappingDocument> claimDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(config.getClaimLease());
        List<CategoryMappingDocument> claimed = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            // Most used first, so the model's attention goes where it changes
            // the most articles.
            Query query = Query.query(Criteria.where(F_STATUS)
                            .in(CategoryMappingStatus.NEW, CategoryMappingStatus.GUESSED)
                            .and(F_NEXT_ATTEMPT_AT).lte(now))
                    .with(Sort.by(Sort.Direction.DESC, "useCount"));
            CategoryMappingDocument mapping = mongoTemplate.findAndModify(query,
                    new Update().set(F_NEXT_ATTEMPT_AT, leaseUntil).inc("attempts", 1),
                    FindAndModifyOptions.options().returnNew(false),
                    CategoryMappingDocument.class);
            if (mapping == null) {
                break;
            }
            claimed.add(mapping);
        }
        return claimed;
    }

    /**
     * Writes what stage two decided.
     *
     * <p>A topic id that the vocabulary does not know is refused rather than
     * stored: a model inventing {@code medtop:99999999} would otherwise put an
     * article into a topic nothing can resolve, and the failure would surface
     * as an empty filter result rather than as an error.
     */
    public void applyResolution(String key, CategoryMappingStatus status,
            @Nullable String topicId, @Nullable String note, Instant now) {

        CategoryMappingStatus effective = status;
        List<String> path = List.of();
        if (status.resolved()) {
            Optional<Topic> topic = topics.find(topicId);
            if (topic.isEmpty()) {
                log.warn("Category '{}': resolver returned unknown topic '{}' — recording as "
                        + "failed", key, topicId);
                fail(key, "unknown topic " + topicId, now);
                return;
            }
            path = topic.get().path();
        } else {
            topicId = null;
        }

        Update update = new Update()
                .set(F_STATUS, effective)
                .set("topicId", topicId)
                .set("topicPath", path)
                .set("confidence", effective.resolved() ? 1.0 : 0)
                .set("decidedBy", "LLM")
                .set("note", note)
                .set("updatedAt", now)
                .unset("lastError")
                // Terminal either way: nothing pending means nothing due.
                .unset(F_NEXT_ATTEMPT_AT);

        mongoTemplate.updateFirst(Query.query(Criteria.where(F_KEY).is(key)), update,
                CategoryMappingDocument.class);
    }

    /** Records a failed attempt, backing off or giving up. */
    public void fail(String key, String error, Instant now) {
        CategoryMappingDocument mapping = repository.findByKey(key).orElse(null);
        if (mapping == null) {
            return;
        }
        boolean exhausted = mapping.getAttempts() >= config.getMaxAttempts();
        Update update = new Update()
                .set("lastError", error)
                .set("updatedAt", now);
        if (exhausted) {
            // FAILED waits for a person or a better prompt rather than
            // retrying on a timer: the input has not changed, so neither will
            // the answer.
            update.set(F_STATUS, CategoryMappingStatus.FAILED).unset(F_NEXT_ATTEMPT_AT);
        } else {
            update.set(F_NEXT_ATTEMPT_AT, now.plus(config.getRetryDelay()));
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where(F_KEY).is(key)), update,
                CategoryMappingDocument.class);
    }

    /** A human decision. Terminal, and never revisited by anything. */
    public CategoryMappingDocument confirm(String key, @Nullable String topicId, Instant now) {
        CategoryMappingDocument mapping = requireByKey(key);
        if (topicId != null && topics.find(topicId).isEmpty()) {
            throw new NotFoundException("topic", topicId);
        }
        applyResolution(mapping.getKey(),
                topicId == null ? CategoryMappingStatus.NOT_A_TOPIC
                        : CategoryMappingStatus.CONFIRMED,
                topicId, "confirmed by hand", now);
        mongoTemplate.updateFirst(Query.query(Criteria.where(F_KEY).is(mapping.getKey())),
                new Update().set("decidedBy", "HUMAN"), CategoryMappingDocument.class);
        return requireByKey(mapping.getKey());
    }

    /** Topic path for one raw category, empty when unresolved. */
    public List<String> topicPathFor(String raw) {
        return findByKey(raw)
                .filter(mapping -> mapping.getStatus().resolved())
                .map(CategoryMappingDocument::getTopicPath)
                .orElseGet(List::of);
    }
}
