package de.mhus.hrafnagud.api.article;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A translated rendering of an article's title and teaser.
 *
 * <p>No translation engine exists yet; the shape is defined now so that the
 * eventual one writes into a place the schema already has, and so that
 * consumers can code against the field today. {@code engine} records which
 * system produced it, because a corpus translated by three different
 * services over two years is otherwise impossible to re-evaluate.
 *
 * <p>The translated body does not live here — it sits with the original
 * body in the content resource, for the same size reason.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleTranslationDto {

    private String title = "";

    private @Nullable String summary;

    /** Identifier of the translating system, e.g. {@code deepl}, {@code gpt-4o}. */
    private String engine = "";

    private @Nullable Instant translatedAt;
}
