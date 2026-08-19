package de.mhus.hrafnagud.munin.source;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository for {@link SourceDocument}. Package-private —
 * {@link SourceService} is the only way in, and the claim and counter
 * operations go through {@code MongoTemplate} because they have to be
 * atomic.
 */
interface SourceRepository extends MongoRepository<SourceDocument, String> {

    Optional<SourceDocument> findByName(String name);

    Optional<SourceDocument> findByUrl(String url);

    List<SourceDocument> findByOriginListName(String originListName);

    long countByEnabled(boolean enabled);

    void deleteByName(String name);
}
