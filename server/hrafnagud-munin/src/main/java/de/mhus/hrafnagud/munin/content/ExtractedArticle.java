package de.mhus.hrafnagud.munin.content;

import lombok.Builder;
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

    /** Lead image, typically from {@code og:image}. */
    @Nullable String imageUrl;

    /** Language the page declared via {@code <html lang>} or Open Graph. */
    @Nullable String language;

    /** Which strategy produced the text — for diagnosing bad extractions. */
    String extractor;

    /**
     * {@code true} when the page carries the markers of a paywall or a
     * consent wall. The text may still be non-empty — it is the teaser plus
     * a subscription pitch — which is exactly why this is a separate signal
     * rather than a word-count threshold.
     */
    boolean gated;
}
