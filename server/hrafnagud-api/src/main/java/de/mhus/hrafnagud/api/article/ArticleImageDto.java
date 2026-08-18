package de.mhus.hrafnagud.api.article;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * An image belonging to an article.
 *
 * <p>Only the URL is exposed; the service never stores image bytes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleImageDto {

    /** Absolute URL. */
    private String url = "";

    /** Caption, or the {@code alt} text when the page had no caption. */
    private @Nullable String caption;

    /**
     * {@code LEAD} — the page's declared representative image, at most one,
     * the safe choice for a thumbnail. {@code INLINE} — found inside the
     * article body, part of the reporting rather than decoration.
     */
    private String role = "";

    /** Declared pixel dimensions, {@code 0} when the page did not say. */
    private int width;

    private int height;
}
