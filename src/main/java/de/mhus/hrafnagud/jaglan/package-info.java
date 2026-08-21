/**
 * Serves the archive as a Jaglan mount: article texts and image bytes under
 * paths Vancetope's document tools can address.
 *
 * <p>The third of the outward-facing packages, alongside {@code centauri}
 * ("what is new?") and {@code zarniwoop} ("what is there about this?"). Jaglan
 * answers the question neither of them can — "give me <em>these</em> bytes
 * under <em>this</em> path" — which for this archive means the one thing in it
 * that is bytes: a stored image, and an article rendered as a file.
 *
 * <p>Imports from {@code munin}, never the reverse. Deleting this package has
 * to leave a compiling collector; {@code ModuleBoundaryTest} enforces it.
 */
@NullMarked
package de.mhus.hrafnagud.jaglan;

import org.jspecify.annotations.NullMarked;
