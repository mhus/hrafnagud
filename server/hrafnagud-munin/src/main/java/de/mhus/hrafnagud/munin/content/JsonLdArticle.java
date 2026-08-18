package de.mhus.hrafnagud.munin.content;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * What a page's {@code schema.org} JSON-LD said about itself.
 *
 * <p>Every field is optional — publishers emit wildly varying subsets, and
 * the point of this type is to carry whichever ones were actually there so
 * the extraction ladder can prefer them over its own guesses.
 */
@Value
@Builder
public class JsonLdArticle {

    @Nullable String headline;

    /**
     * The article text as the publisher declared it. Frequently absent even
     * when everything else is present, which is why it is treated as a
     * bonus rather than as the reason to parse JSON-LD at all.
     */
    @Nullable String articleBody;

    @Nullable String description;

    /** Image URLs in declaration order; the first is the lead image. */
    @Singular
    List<String> images;

    @Nullable Instant datePublished;

    @Nullable String author;

    /** BCP-47 tag from {@code inLanguage}, un-normalised. */
    @Nullable String language;

    /** {@code articleSection} values — the publisher's own categorisation. */
    @Singular
    List<String> sections;

    /** True when a block was found and parsed, even if every field was empty. */
    boolean present;

    static JsonLdArticle absent() {
        return JsonLdArticle.builder().present(false).build();
    }
}
