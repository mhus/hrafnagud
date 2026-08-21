/**
 * Copies of the images an article references.
 *
 * <p>Munin's answer to the one part of an article that rots while the text
 * keeps: the publisher's image URL. Storing the bytes is optional
 * ({@code munin.image.enabled}) and per-image — what was never stored is
 * referenced by its original URL exactly as before.
 */
@NullMarked
package de.mhus.hrafnagud.munin.image;

import org.jspecify.annotations.NullMarked;
