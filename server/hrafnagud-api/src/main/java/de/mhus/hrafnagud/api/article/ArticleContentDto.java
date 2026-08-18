package de.mhus.hrafnagud.api.article;

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
 * The extracted body of an article, as its own resource.
 *
 * <p>Plain text, not HTML: what downstream consumers want is the prose, and
 * keeping markup would mean keeping every publisher's wrapper markup along
 * with it. Paragraph structure survives as blank lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleContentDto {

    private String articleId = "";

    /** Extracted prose. Paragraphs separated by blank lines. */
    private String text = "";

    private int wordCount;

    /**
     * Title as found on the page, which is regularly better than the feed's
     * (feeds truncate, prefix with the section, or append the brand).
     */
    private @Nullable String extractedTitle;

    /** Lead image declared by the page, typically {@code og:image}. */
    private @Nullable String imageUrl;

    /** URL actually delivered after redirects. */
    private @Nullable String finalUrl;

    /** Name of the extraction strategy that produced the text. */
    private @Nullable String extractor;

    private @Nullable Instant fetchedAt;

    /** Translated bodies keyed by BCP-47 primary subtag. */
    private Map<String, String> translations = new LinkedHashMap<>();
}
