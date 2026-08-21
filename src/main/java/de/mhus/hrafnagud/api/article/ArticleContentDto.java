package de.mhus.hrafnagud.api.article;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Lead image URL, also present as the {@code LEAD} entry in {@link #images}. */
    private @Nullable String imageUrl;

    /** Every image belonging to the article: the lead one, then the inline ones. */
    private List<ArticleImageDto> images = new ArrayList<>();

    /** Byline the page declared. */
    private @Nullable String author;

    /** Publication time the page declared — usually better than the feed's. */
    private @Nullable Instant publishedAt;

    /** Language the page declared, as a BCP-47 primary subtag. */
    private @Nullable String language;

    /** Canonical URL the page claims for itself. Informational. */
    private @Nullable String canonicalUrl;

    /** URL actually delivered after redirects. */
    private @Nullable String finalUrl;

    /**
     * Which rung of the extraction ladder produced the text:
     * {@code json-ld} (the publisher's own metadata), {@code semantic} (a
     * container the page marks as its body), {@code scored} (highest
     * paragraph text over link density) or {@code body} (last resort).
     * Aggregating on this shows which publishers fall through to the
     * guessing rungs.
     */
    private @Nullable String extractor;

    private @Nullable Instant fetchedAt;

    /** Translated bodies keyed by BCP-47 primary subtag. */
    private Map<String, String> translations = new LinkedHashMap<>();
}
