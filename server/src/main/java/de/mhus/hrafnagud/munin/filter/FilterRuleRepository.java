package de.mhus.hrafnagud.munin.filter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data access to {@code filter_rules}. Owned by {@link FilterRuleService}. */
public interface FilterRuleRepository extends MongoRepository<FilterRuleDocument, String> {

    Optional<FilterRuleDocument> findByName(String name);

    List<FilterRuleDocument> findByEnabledTrue();

    List<FilterRuleDocument> findAll(Sort sort);
}
