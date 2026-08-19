/**
 * Enrichment contract: results of processing steps applied to an article.
 *
 * <p>Separate from the article itself because a processing result is not
 * a property of the news — it is the output of one run of one model at
 * one point in time, and running it again with a better model should
 * produce a second result rather than destroy the first.
 */
@NullMarked
package de.mhus.hrafnagud.api.enrichment;

import org.jspecify.annotations.NullMarked;
