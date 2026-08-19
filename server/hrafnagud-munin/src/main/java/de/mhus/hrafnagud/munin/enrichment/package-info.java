/**
 * Results of processing steps applied to articles.
 *
 * <p>A separate collection rather than fields on the article, because a
 * processing result is not a property of the news: it is what one model
 * produced at one point in time. Re-running a stage a year later with a
 * better model should add a result, not destroy the earlier one — that is
 * the only way to tell whether the new model is actually better.
 *
 * <p>It is also what keeps the article schema still. Every later stage —
 * keywords, sentiment, embeddings — is a new {@code EnrichmentType} and a
 * new worker, and touches nothing that already exists.
 */
@NullMarked
package de.mhus.hrafnagud.munin.enrichment;

import org.jspecify.annotations.NullMarked;
