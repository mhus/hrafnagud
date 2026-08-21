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
 * <p>A URL and what the page said about it — never the bytes. A reference is
 * cheap and uncontroversial; keeping publishers' image files is a storage
 * question and a copyright question at once, and the URL leaves both open
 * where the reverse would not.
 *
 * <p>Whether a copy of the bytes exists is a separate, switchable subsystem
 * ({@code de.mhus.hrafnagud.munin.image}) that reads this list and never
 * writes to it: the address of a copy is derived from {@link #url}, so an
 * image that was stored and one that was not look identical here. That is
 * deliberate — nothing about an article may depend on a copy existing.
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
