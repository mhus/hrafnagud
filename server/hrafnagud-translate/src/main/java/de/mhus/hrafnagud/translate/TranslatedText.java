package de.mhus.hrafnagud.translate;

import lombok.Value;
import org.jspecify.annotations.Nullable;

/** What a provider returned for one article. */
@Value
public class TranslatedText {

    String title;

    /** {@code null} when the article had no teaser, or none was asked for. */
    @Nullable String summary;
}
