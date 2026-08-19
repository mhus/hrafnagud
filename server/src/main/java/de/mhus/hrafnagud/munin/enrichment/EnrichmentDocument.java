package de.mhus.hrafnagud.munin.enrichment;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One processing result for one article.
 *
 * <p>Deliberately <b>not</b> unique per {@code (articleId, type,
 * language)}. Several results may coexist and the newest wins on read;
 * that is what makes a stage re-runnable, which is the whole reason this
 * collection exists rather than a map on the article.
 *
 * <p>There is no {@code current} flag for the same reason there is no
 * cached copy on the article: a second place that says which result
 * counts is a second place that can be wrong. Ordering by
 * {@link #createdAt} cannot drift.
 */
@Document(collection = "enrichments")
@CompoundIndexes({
        // The read: latest result of a type for an article. Also serves
        // the batch lookup that fills a page of articles in one query.
        @CompoundIndex(name = "article_type_idx",
                def = "{ 'articleId': 1, 'type': 1, 'createdAt': -1 }"),
        // "What did model X produce" — the comparison this collection is
        // for once a stage has been run twice.
        @CompoundIndex(name = "type_model_idx", def = "{ 'type': 1, 'model': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentDocument {

    @Id
    private @Nullable String id;

    /** {@code ArticleDocument.id} this result belongs to. */
    private String articleId = "";

    private EnrichmentType type = EnrichmentType.TRANSLATION;

    /** Component that produced it, e.g. {@code vance-ode}. Always known. */
    private String producer = "";

    /** Model behind the producer, when it reported one. */
    private @Nullable String model;

    /** Target language, for a translation. */
    private @Nullable String language;

    private Instant createdAt = Instant.EPOCH;

    /**
     * Payload, shaped by {@link #type}. A map rather than a typed field
     * per stage: the shapes differ per type and would otherwise force
     * either a sparse document or a class hierarchy that Mongo has to be
     * taught about — for data whose only consumer already knows what it
     * asked for.
     */
    @Builder.Default
    private Map<String, Object> content = new LinkedHashMap<>();
}
