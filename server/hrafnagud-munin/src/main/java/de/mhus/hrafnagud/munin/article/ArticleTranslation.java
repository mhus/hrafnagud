package de.mhus.hrafnagud.munin.article;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Embedded translation of an article's title and teaser.
 *
 * <p>Stored inside {@link ArticleDocument} as a map keyed by language tag.
 * Language tags contain no dots, so they are safe as MongoDB map keys.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTranslation {

    private String title = "";

    private @Nullable String summary;

    /** Which system produced this, e.g. {@code deepl}. */
    private String engine = "";

    private @Nullable Instant translatedAt;
}
