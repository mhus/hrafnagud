/**
 * The source registry: which feeds exist, how often each is polled, and how
 * the poll interval adapts to what the feed actually delivers.
 *
 * <p>Colocated per the same convention as the rest of the service: document,
 * package-private repository, and the service that owns the data. Nothing
 * outside this package touches the {@code sources} collection.
 */
@NullMarked
package de.mhus.hrafnagud.munin.source;

import org.jspecify.annotations.NullMarked;
