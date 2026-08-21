package de.mhus.hrafnagud.munin.enrichment;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code enrichments} collection.
 *
 * <p>Writes only ever append. A stage that runs again produces another
 * document; nothing here updates or replaces an earlier result, because
 * being able to compare two runs is the reason the collection exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnrichmentService {

    private static final String F_ARTICLE_ID = "articleId";
    private static final String F_TYPE = "type";
    private static final String F_CREATED_AT = "createdAt";

    private final EnrichmentRepository repository;
    private final MongoTemplate mongoTemplate;

    /** Appends a result. Never replaces an earlier one. */
    public EnrichmentDocument record(EnrichmentDocument enrichment) {
        return repository.save(enrichment);
    }

    /** The most recent result of a type for one article. */
    public Optional<EnrichmentDocument> latest(String articleId, EnrichmentType type) {
        Query query = Query.query(Criteria.where(F_ARTICLE_ID).is(articleId).and(F_TYPE).is(type))
                .with(Sort.by(Sort.Direction.DESC, F_CREATED_AT))
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, EnrichmentDocument.class));
    }

    /** Every result for one article, newest first — the audit view. */
    public List<EnrichmentDocument> allFor(String articleId) {
        return mongoTemplate.find(
                Query.query(Criteria.where(F_ARTICLE_ID).is(articleId))
                        .with(Sort.by(Sort.Direction.DESC, F_CREATED_AT)),
                EnrichmentDocument.class);
    }

    /**
     * The most recent result of a type for each of many articles.
     *
     * <p>One query for a whole page rather than one per article. The
     * grouping happens here instead of in an aggregation because
     * "latest per group" costs a pipeline in Mongo and a first-wins loop
     * in Java, and a page is at most a couple of hundred rows.
     */
    public Map<String, EnrichmentDocument> latestForEach(
            Collection<String> articleIds, EnrichmentType type) {

        if (articleIds.isEmpty()) {
            return Map.of();
        }
        List<EnrichmentDocument> found = mongoTemplate.find(
                Query.query(Criteria.where(F_ARTICLE_ID).in(articleIds).and(F_TYPE).is(type))
                        .with(Sort.by(Sort.Direction.DESC, F_CREATED_AT)),
                EnrichmentDocument.class);

        Map<String, EnrichmentDocument> latest = new LinkedHashMap<>();
        for (EnrichmentDocument enrichment : found) {
            // Sorted newest first, so the first one seen per article wins.
            latest.putIfAbsent(enrichment.getArticleId(), enrichment);
        }
        return latest;
    }

    public long countByType(EnrichmentType type) {
        return repository.countByType(type);
    }

    /** Drops everything derived from an article. Called when it is deleted. */
    public void deleteForArticle(String articleId) {
        repository.deleteByArticleId(articleId);
    }
}
