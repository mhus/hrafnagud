package de.mhus.hrafnagud.translate;

import lombok.Value;
import org.jspecify.annotations.Nullable;

/** What a provider returned for one article. */
@Value
public class TranslatedText {

    String title;

    /** {@code null} when the article had no teaser, or none was asked for. */
    @Nullable String summary;

    /**
     * The model that produced this text, when the provider can say —
     * per result, not per provider, because a provider with a fallback
     * chain answers with different models on different calls, and the
     * call where they differ is exactly the one worth being able to
     * identify later.
     *
     * <p>{@code null} means unknown and must be stored as unknown. A
     * plausible-looking default here would make the enrichment record
     * worse than an empty one: it would look like evidence.
     */
    @Nullable String model;
}
