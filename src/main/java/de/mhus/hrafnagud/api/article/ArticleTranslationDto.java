package de.mhus.hrafnagud.api.article;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * An article's title and teaser in the pivot language.
 *
 * <p>A flattened view of the newest {@code TRANSLATION} enrichment, for
 * callers that want to read the archive rather than audit how it was
 * produced. {@code producer} and {@code model} are carried along because
 * a corpus translated by three systems over two years is otherwise
 * impossible to re-evaluate; everything else about the run is at
 * {@code GET /articles/{id}/enrichments}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleTranslationDto {

    private String title = "";

    private @Nullable String summary;

    private @Nullable String language;

    /** Component that produced it, e.g. {@code vance-ode}. */
    private String producer = "";

    /** Model behind the producer, when it reported one. */
    private @Nullable String model;

    private @Nullable Instant translatedAt;
}
