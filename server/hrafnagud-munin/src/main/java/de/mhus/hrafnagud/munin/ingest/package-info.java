/**
 * Collection: reading a source, turning what it returned into article
 * candidates, and the loop that decides when to do so.
 *
 * <p>The reader SPI is what keeps "how do we read this kind of source" out
 * of "what do we do with what it returned". Today there is one reader; the
 * ingest path already does not know that.
 */
@NullMarked
package de.mhus.hrafnagud.munin.ingest;

import org.jspecify.annotations.NullMarked;
