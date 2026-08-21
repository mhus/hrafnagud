/**
 * Pure helpers with no Spring, no IO and no state: URL normalisation,
 * hashing, slugs and text cleanup.
 *
 * <p>These carry most of the correctness risk in the ingest path — a
 * mistake in {@link de.mhus.hrafnagud.munin.util.UrlNormalizer} either
 * splits one article into ten or merges ten into one — so they are kept
 * side-effect free and are tested directly.
 */
@NullMarked
package de.mhus.hrafnagud.munin.util;

import org.jspecify.annotations.NullMarked;
