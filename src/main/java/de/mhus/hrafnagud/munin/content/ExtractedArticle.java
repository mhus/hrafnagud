package de.mhus.hrafnagud.munin.content;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** What {@link ContentExtractor} recovered from a publisher's page. */
@Value
@Builder
public class ExtractedArticle {

    /** Prose, paragraphs separated by blank lines. */
    String text;

    int wordCount;

    /** Page title, usually cleaner than the feed's. */
    @Nullable String title;

    /**
     * Images belonging to the article: at most one {@link ImageRole#LEAD},
     * then the inline ones in document order.
     */
    @Singular
    List<ExtractedImage> images;

    /** Language the page declared, normalised to a primary subtag. */
    @Nullable String language;

    /** Byline, where the page declared one. */
    @Nullable String author;

    /** Publication time the page declared — more reliable than a feed's. */
    @Nullable Instant publishedAt;

    /**
     * Canonical URL the page claims for itself. Not acted on: changing an
     * article's identity after ingest would move it under a unique index and
     * possibly merge two rows, which is a decision for its own change rather
     * than a side effect of fetching a body.
     */
    @Nullable String canonicalUrl;

    /**
     * Which rung of the ladder produced the text — {@code json-ld},
     * {@code semantic}, {@code scored} or {@code body}. Stored so that
     * extraction quality becomes measurable per publisher: aggregating on it
     * shows which sites fall through to the guessing rungs, and those are
     * the ones worth looking at.
     */
    String extractor;

    /**
     * {@code true} when the page carries paywall or consent-wall markers.
     * The text may still be non-empty — it is the teaser plus a subscription
     * pitch — which is why this is a separate signal rather than a word-count
     * threshold.
     */
    boolean gated;

    /** The lead image, when one was found. */
    public @Nullable ExtractedImage leadImage() {
        return images.stream()
                .filter(image -> image.getRole() == ImageRole.LEAD)
                .findFirst()
                .orElse(null);
    }
}
