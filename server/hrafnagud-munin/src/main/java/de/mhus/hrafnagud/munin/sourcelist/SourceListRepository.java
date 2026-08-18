package de.mhus.hrafnagud.munin.sourcelist;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link SourceListDocument}. Package-private —
 * {@link SourceListService} owns this collection.
 */
interface SourceListRepository extends MongoRepository<SourceListDocument, String> {

    Optional<SourceListDocument> findByName(String name);

    Optional<SourceListDocument> findByUrl(String url);
}
