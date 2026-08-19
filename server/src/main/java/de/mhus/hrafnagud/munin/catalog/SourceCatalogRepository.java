package de.mhus.hrafnagud.munin.catalog;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data access to {@code source_catalogs}. Owned by {@link SourceCatalogService}. */
public interface SourceCatalogRepository extends MongoRepository<SourceCatalogDocument, String> {

    Optional<SourceCatalogDocument> findByName(String name);

    Optional<SourceCatalogDocument> findByUrl(String url);
}
