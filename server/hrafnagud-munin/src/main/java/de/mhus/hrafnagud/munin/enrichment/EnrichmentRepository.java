package de.mhus.hrafnagud.munin.enrichment;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link EnrichmentDocument}. Package-private —
 * {@link EnrichmentService} owns this collection, and the latest-per-group
 * reads go through {@code MongoTemplate}.
 */
interface EnrichmentRepository extends MongoRepository<EnrichmentDocument, String> {

    long countByType(de.mhus.hrafnagud.api.enrichment.EnrichmentType type);

    void deleteByArticleId(String articleId);
}
