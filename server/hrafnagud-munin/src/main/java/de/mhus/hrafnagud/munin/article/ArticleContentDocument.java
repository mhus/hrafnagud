package de.mhus.hrafnagud.munin.article;

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

    /**
     * Lead image URL, kept as a flat field alongside {@link #images} because
     * a thumbnail is what most consumers want and making them search the
     * list for the {@code LEAD} entry would be needless work.
     */
    private @Nullable String imageUrl;

    /** Every image belonging to the article: the lead one, then the inline ones. */
    private List<ArticleImage> images = new ArrayList<>();

    /** Byline the page declared. Feeds frequently omit this; pages rarely do. */
    private @Nullable String author;

    /**
     * Publication time the page declared, typically from {@code schema.org}
     * metadata. More reliable than a feed's {@code pubDate}, but kept here
     * rather than overwriting the article's own field — the article's
     * timestamps were set at ingest and rewriting them later would make
     * ordering depend on when a body happened to be fetched.
     */
    private @Nullable Instant publishedAt;

    /** Language the page declared, normalised to a primary subtag. */
    private @Nullable String language;

    /**
     * Canonical URL the page claims for itself. Recorded, not acted on:
     * changing an article's identity would move it under a unique index and
     * possibly merge two rows, which deserves its own deliberate change
     * rather than being a side effect of fetching a body.
     */
    private @Nullable String canonicalUrl;

    /** URL after redirects. */
    private @Nullable String finalUrl;

    /** Name of the extraction strategy that produced the text. */
    private @Nullable String extractor;

    private Instant fetchedAt = Instant.EPOCH;

    /** Translated bodies keyed by BCP-47 primary subtag. */
    private Map<String, String> translations = new LinkedHashMap<>();
}
