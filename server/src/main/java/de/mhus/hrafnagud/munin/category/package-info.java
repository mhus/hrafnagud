/**
 * Categories: what publishers called their sections, and the normalised topic
 * behind it.
 *
 * <p>The original is never touched — {@code ArticleDocument.categories} stays
 * verbatim. Everything here is additive, and everything it decides lives in a
 * mapping table keyed by the raw string, so a decision is made once for the
 * whole archive rather than per article. See specs/categories.md.
 */
@NullMarked
package de.mhus.hrafnagud.munin.category;

import org.jspecify.annotations.NullMarked;
