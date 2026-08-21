package de.mhus.hrafnagud.munin.image;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Images by their derived id.
 *
 * <p>Thin by intention: every read that does not want the bytes goes through
 * {@link ImageService}, which projects them away. A finder added here returns
 * whole documents, and a whole document is an image file.
 */
public interface ImageRepository extends MongoRepository<ImageDocument, String> {

    Optional<ImageDocument> findByUrl(String url);
}
