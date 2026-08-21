package de.mhus.hrafnagud.munin.image;

import de.mhus.hrafnagud.munin.article.ArticleImage;
import de.mhus.hrafnagud.settings.Settings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Owns {@link ImageDocument}: the queue, the bytes, and the answer to "does
 * the archive have this image?".
 *
 * <p>The one rule that shapes this class is that the bytes live in the
 * document (see {@link ImageDocument}), so every read states whether it wants
 * them. {@link #stat(String)} and {@link #claimDue} project them away;
 * {@link #load(String)} is the only method that returns an image file, and it
 * exists to be called once per served request.
 */
@Service
@Slf4j
public class ImageService {

    private static final String F_STATUS = "status";
    private static final String F_NEXT_ATTEMPT_AT = "nextAttemptAt";
    private static final String F_ATTEMPTS = "attempts";
    private static final String F_DATA = "data";

    /** Role name the extractor uses for the article's representative image. */
    private static final String ROLE_LEAD = "LEAD";

    private final ImageRepository repository;
    private final MongoTemplate mongoTemplate;
    private final Settings.Image config;

    public ImageService(ImageRepository repository, MongoTemplate mongoTemplate,
            Settings settings) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.config = settings.getImage();
    }

    // ─── Queueing ───

    /**
     * Queues the images of one article for copying.
     *
     * <p>Nothing is queued while {@code munin.image.enabled} is off, which is
     * the opposite of how article bodies work — ingest queues every article
     * regardless of whether the body fetcher runs. The difference is what a
     * queue entry costs: for an article it is a status field on a document
     * that exists anyway, while here it is a new document per image. Creating
     * millions of them for a feature that is switched off would be storing a
     * decision rather than recording a fact.
     *
     * <p>Nothing is lost by that, because the image list is kept on the
     * article's content document: whatever was extracted while this was off
     * can be queued from stored data, no queue record needed in advance.
     *
     * @return how many images were newly queued
     */
    public int enqueue(String articleId, List<ArticleImage> images, Instant now) {
        if (!config.enabled().value() || images.isEmpty()) {
            return 0;
        }
        boolean leadOnly = config.leadOnly().value();
        int queued = 0;
        for (ArticleImage image : images) {
            if (StringUtils.isBlank(image.getUrl())) {
                continue;
            }
            if (leadOnly && !ROLE_LEAD.equalsIgnoreCase(image.getRole())) {
                continue;
            }
            if (queue(articleId, image, now)) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * Upserts one queue entry.
     *
     * <p>{@code setOnInsert} throughout: an image already in the archive must
     * not be reset to pending because a second article referenced it, and one
     * that failed must not be retried for the same reason — the retry
     * schedule belongs to the image, not to whoever mentions it.
     *
     * @return {@code true} when this call created the entry
     */
    private boolean queue(String articleId, ArticleImage image, Instant now) {
        String id = ImageKey.of(image.getUrl());
        Update update = new Update()
                .setOnInsert("url", image.getUrl())
                .setOnInsert(F_STATUS, ImageStatus.PENDING)
                .setOnInsert(F_NEXT_ATTEMPT_AT, now)
                .setOnInsert(F_ATTEMPTS, 0)
                .setOnInsert("firstSeenAt", now)
                .setOnInsert("firstArticleId", articleId)
                .setOnInsert("role", StringUtils.defaultString(image.getRole()))
                .setOnInsert("width", image.getWidth())
                .setOnInsert("height", image.getHeight())
                .setOnInsert("size", 0L);

        var result = mongoTemplate.upsert(Query.query(Criteria.where("_id").is(id)), update,
                ImageDocument.class);
        return result.getUpsertedId() != null;
    }

    // ─── The queue ───

    /**
     * Atomically claims up to {@code limit} images that are due.
     *
     * <p>Same shape as the source and content queues: the claim pushes
     * {@code nextAttemptAt} out by the lease, so a worker that dies holds the
     * image for the lease and not forever. Bytes are projected away — a
     * claimed image has none yet, and asking for them would make the claim
     * query carry the field for nothing.
     */
    public List<ImageDocument> claimDue(Instant now, int limit) {
        Instant leaseUntil = now.plus(config.claimLease().value());
        List<ImageDocument> claimed = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Query query = Query.query(Criteria.where(F_STATUS).is(ImageStatus.PENDING)
                            .and(F_NEXT_ATTEMPT_AT).lte(now))
                    .with(Sort.by(Sort.Direction.ASC, F_NEXT_ATTEMPT_AT));
            query.fields().exclude(F_DATA);

            ImageDocument image = mongoTemplate.findAndModify(query,
                    new Update().set(F_NEXT_ATTEMPT_AT, leaseUntil).inc(F_ATTEMPTS, 1),
                    FindAndModifyOptions.options().returnNew(true),
                    ImageDocument.class);
            if (image == null) {
                break;
            }
            claimed.add(image);
        }
        return claimed;
    }

    /** Stores the bytes and marks the image {@code STORED}. */
    public void recordStored(String id, byte[] data, @Nullable String mime, Instant now) {
        Update update = new Update()
                .set(F_STATUS, ImageStatus.STORED)
                .set(F_DATA, data)
                .set("size", (long) data.length)
                .set("mime", mime)
                .set("contentHash", ImageKey.ofBytes(data))
                .set("storedAt", now)
                .unset(F_NEXT_ATTEMPT_AT)
                .unset("error");
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update,
                ImageDocument.class);
    }

    /**
     * Records a failed attempt, scheduling a retry or giving up.
     *
     * <p>Giving up is cheaper here than anywhere else in the collector: the
     * article keeps referencing the publisher URL, so a {@code FAILED} image
     * costs the independence from that URL and nothing else. There is no
     * reason to keep hammering a host for it.
     */
    public void recordFailure(String id, String error, int attempts, Instant now) {
        boolean giveUp = attempts >= config.maxAttempts().value();
        Update update = new Update()
                .set(F_STATUS, giveUp ? ImageStatus.FAILED : ImageStatus.PENDING)
                .set("error", StringUtils.abbreviate(error, 300));
        if (giveUp) {
            update.unset(F_NEXT_ATTEMPT_AT);
        } else {
            // Doubling per attempt, like the body fetcher. attempts is the
            // count including the one that just failed.
            long seconds = config.retryDelay().value().toSeconds()
                    * (1L << Math.min(attempts - 1, 6));
            update.set(F_NEXT_ATTEMPT_AT, now.plusSeconds(seconds));
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), update,
                ImageDocument.class);
    }

    // ─── Reading ───

    /**
     * Metadata for one image URL, without the bytes.
     *
     * <p>This is the question a serving path asks: is there a local copy, and
     * what is it? A miss — never queued, still pending, failed — means the
     * caller uses the publisher URL, which is why the answer is an
     * {@link Optional} of metadata rather than a boolean.
     */
    public Optional<ImageDocument> stat(String url) {
        Query query = Query.query(Criteria.where("_id").is(ImageKey.of(url)));
        query.fields().exclude(F_DATA);
        return Optional.ofNullable(mongoTemplate.findOne(query, ImageDocument.class));
    }

    /**
     * The bytes of a stored image.
     *
     * <p>The only method that loads an image file. Empty when the archive has
     * no copy, including when a record exists but is pending or failed.
     */
    public Optional<ImageDocument> load(String url) {
        return repository.findById(ImageKey.of(url))
                .filter(image -> image.getStatus().stored() && image.getData() != null);
    }

    /** Counts per status, for the stats endpoint and the console. */
    public Map<ImageStatus, Long> countByStatus() {
        Map<ImageStatus, Long> counts = new LinkedHashMap<>();
        for (ImageStatus status : ImageStatus.values()) {
            long count = mongoTemplate.count(Query.query(Criteria.where(F_STATUS).is(status)),
                    ImageDocument.class);
            if (count > 0) {
                counts.put(status, count);
            }
        }
        return counts;
    }

    /**
     * Bytes held across all stored images — the number that decides retention.
     *
     * <p>Summed over the {@code size} field rather than measured, which is
     * what makes it affordable to ask: {@code $group} over a projected number
     * never touches a {@code data} field. {@code $sum} and {@code $group} are
     * both well inside what MongoDB 4.4 offers.
     */
    public long storedBytes() {
        Document total = mongoTemplate.aggregate(
                Aggregation.newAggregation(
                        Aggregation.match(Criteria.where(F_STATUS).is(ImageStatus.STORED)),
                        Aggregation.group().sum("size").as("total")),
                ImageDocument.class, Document.class).getUniqueMappedResult();
        return total == null ? 0L : ((Number) total.getOrDefault("total", 0L)).longValue();
    }
}
