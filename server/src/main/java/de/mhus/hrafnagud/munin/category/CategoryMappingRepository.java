package de.mhus.hrafnagud.munin.category;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data access to {@code category_mappings}. Owned by {@link CategoryMappingService}. */
public interface CategoryMappingRepository
        extends MongoRepository<CategoryMappingDocument, String> {

    Optional<CategoryMappingDocument> findByKey(String key);
}
