package de.mhus.hrafnagud.settings;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Stored setting overrides, keyed by their dotted name. */
public interface SettingRepository extends MongoRepository<SettingDocument, String> {

    Optional<SettingDocument> findByKey(String key);

    void deleteByKey(String key);
}
