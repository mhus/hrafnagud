package de.mhus.hrafnagud.munin.article;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link ArticleDocument}. Package-private —
 * {@link ArticleService} owns this collection, and the ingest upsert plus
 * the content-queue claim go through {@code MongoTemplate} because they
 * have to be atomic.
 */
interface ArticleRepository extends MongoRepository<ArticleDocument, String> {

    Optional<ArticleDocument> findByDedupKey(String dedupKey);
}
