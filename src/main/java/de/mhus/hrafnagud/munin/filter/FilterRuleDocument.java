package de.mhus.hrafnagud.munin.filter;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterMatchType;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterRuleType;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One rule of one rule set.
 *
 * <p>A row in the database rather than a line in a config file, because these
 * are written while looking at the data. A redeploy per rule is the difference
 * between a filter that gets tuned and a filter that gets abandoned.
 *
 * <p>There is no ordering field, and that is the load-bearing part of the
 * design: evaluation asks "does any accept rule match, then does any deny rule
 * match" (specs/filter.md §2.1), so nothing about one rule depends on which
 * others exist. A priority column would take that away, and with it the
 * property that lets a rule set grow without becoming a program.
 */
@Document(collection = "filter_rules")
@CompoundIndexes({
        @CompoundIndex(name = "filter_name_idx", def = "{ 'name': 1 }", unique = true),
        // The whole working set is loaded into memory, so this index serves the
        // load and the console listing rather than any per-article lookup.
        @CompoundIndex(name = "filter_pipeline_idx", def = "{ 'pipeline': 1, 'enabled': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterRuleDocument {

    @Id
    private @Nullable String id;

    /**
     * Technical identity. An article records the name of the rule that decided
     * it, so this is what makes "why is this not translated" answerable — and
     * why renaming a rule is not offered.
     */
    private String name = "";

    private FilterPipeline pipeline = FilterPipeline.TRANSLATION;

    private FilterDecision decision = FilterDecision.DENY;

    private FilterRuleType type = FilterRuleType.HOST;

    private FilterMatchType matchType = FilterMatchType.EXACT;

    /** Compared case-insensitively; a regex is validated before this is stored. */
    private String value = "";

    private boolean enabled = true;

    private @Nullable String note;

    private Instant createdAt = Instant.EPOCH;

    private Instant updatedAt = Instant.EPOCH;

    @Version
    private @Nullable Long version;
}
