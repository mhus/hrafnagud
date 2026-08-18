package de.mhus.hrafnagud.munin.article;

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
 * The extracted body of an article, in its own collection.
 *
 * <p>Plain text rather than HTML. What consumers want from a news archive
 * is the prose; keeping markup would mean keeping each publisher's wrapper
 * structure with it, and any later processing would begin by stripping it
 * anyway. Paragraphs survive as blank lines.
 */
@Document(collection = "article_contents")
@CompoundIndexes({
        @CompoundIndex(name = "article_idx", def = "{ 'articleId': 1 }", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleContentDocument {

    @Id
    private @Nullable String id;

    /** {@code ArticleDocument.id} this body belongs to. Unique. */
    private String articleId = "";

    private String text = "";

    private int wordCount;

    /** Title as found on the page — regularly better than the feed's. */
    private @Nullable String extractedTitle;

    /** Lead image declared by the page, typically {@code og:image}. */
    private @Nullable String imageUrl;

    /** URL after redirects. */
    private @Nullable String finalUrl;

    /** Name of the extraction strategy that produced the text. */
    private @Nullable String extractor;

    private Instant fetchedAt = Instant.EPOCH;

    /** Translated bodies keyed by BCP-47 primary subtag. */
    private Map<String, String> translations = new LinkedHashMap<>();
}
