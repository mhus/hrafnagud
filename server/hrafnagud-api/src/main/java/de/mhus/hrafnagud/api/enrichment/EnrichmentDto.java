package de.mhus.hrafnagud.api.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One processing result for one article.
 *
 * <p>Several may exist for the same article and type — that is the
 * point. Re-running a stage with a better model adds a result rather
 * than replacing one, so the two can be compared and the older one is
 * still there when the newer turns out worse. Readers take the most
 * recent unless they say otherwise.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnrichmentDto {

    private String id = "";

    private String articleId = "";

    private EnrichmentType type = EnrichmentType.TRANSLATION;

    /**
     * Which component produced it, e.g. {@code vance-ode}. Coarser than
     * {@link #model} and always known.
     */
    private String producer = "";

    /**
     * The model behind the producer when it reported one. Kept because a
     * corpus processed by three models over two years is otherwise
     * impossible to re-evaluate.
     */
    private @Nullable String model;

    /** Target language for a {@link EnrichmentType#TRANSLATION}. */
    private @Nullable String language;

    private @Nullable Instant createdAt;

    /**
     * The payload, shaped by {@link #type}. For a translation:
     * {@code title} and {@code summary}.
     */
    private Map<String, Object> content = new LinkedHashMap<>();
}
