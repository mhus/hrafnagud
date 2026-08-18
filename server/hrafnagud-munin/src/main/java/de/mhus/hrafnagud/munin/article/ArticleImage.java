package de.mhus.hrafnagud.munin.article;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * An image belonging to an article, embedded in
 * {@link ArticleContentDocument}.
 *
 * <p>URL only — the bytes are never stored. A reference is cheap and
 * uncontroversial; keeping publishers' image files is a storage question and
 * a copyright question at once. The URL leaves that decision open, and the
 * reverse would not.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleImage {

    private String url = "";

    /**
     * Caption where the page had one, {@code alt} text otherwise. In news
     * writing this is often the most informative sentence about the image,
     * which is why it is kept rather than folded into the body.
     */
    private @Nullable String caption;

    /** {@code LEAD} for the declared representative image, {@code INLINE} otherwise. */
    private String role = "";

    private int width;

    private int height;
}
