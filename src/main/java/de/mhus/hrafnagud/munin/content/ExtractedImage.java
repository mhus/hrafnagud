package de.mhus.hrafnagud.munin.content;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * An image belonging to an article.
 *
 * <p>Only the URL is stored, never the bytes. Keeping a reference is cheap
 * and uncontroversial; downloading and storing publishers' images is a
 * storage question and a copyright question at once, and the URL leaves that
 * decision open — the reverse would not.
 */
@Value
@Builder
public class ExtractedImage {

    /** Absolute URL, resolved against the page it was found on. */
    String url;

    /**
     * Caption, from a {@code <figcaption>} where there is one and the
     * {@code alt} attribute otherwise. In news writing the caption is often
     * the most informative sentence about the image, which is why it is a
     * field of its own rather than being folded into the body text.
     */
    @Nullable String caption;

    ImageRole role;

    /** Declared pixel dimensions, {@code 0} when the page did not say. */
    int width;

    int height;
}
