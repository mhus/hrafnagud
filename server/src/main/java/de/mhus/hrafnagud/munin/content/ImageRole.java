package de.mhus.hrafnagud.munin.content;

/**
 * Why an image is attached to an article.
 *
 * <ul>
 *   <li>{@link #LEAD} — the page's declared representative image, from
 *       JSON-LD or Open Graph. There is at most one, and it is the safe
 *       choice for a thumbnail.</li>
 *   <li>{@link #INLINE} — found inside the extracted article body. Part of
 *       the reporting rather than a decoration, and usually the one that
 *       carries a caption.</li>
 * </ul>
 *
 * <p>The distinction matters because the two are trustworthy in different
 * ways: the lead image is what the publisher chose to represent the piece,
 * while inline images are what the piece actually shows.
 */
public enum ImageRole {

    /** Declared representative image. */
    LEAD,

    /** Image within the article body. */
    INLINE
}
