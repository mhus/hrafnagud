/**
 * The archive: collected articles, their bodies, and the deduplicating
 * ingest path that writes them.
 *
 * <p>Metadata and body are separate collections. A body is roughly fifty
 * times the size of the metadata around it and is wanted in a fraction of
 * the queries, so keeping them together would mean every list query, every
 * index and every scan drags the text along.
 */
@NullMarked
package de.mhus.hrafnagud.munin.article;

import org.jspecify.annotations.NullMarked;
