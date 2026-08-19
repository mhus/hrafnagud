/**
 * Source lists: documents that enumerate feeds, their parsers, and the
 * refresh that reconciles the registry against them.
 *
 * <p>Keeping a worldwide registry by hand does not work, so the registry is
 * fed from directories — OPML exports and plain URL lists. The interesting
 * part is not the parsing but the reconciliation: what happens to a source
 * the list no longer mentions, and what happens to a field a human has
 * changed since the import. Both answers live in
 * {@link de.mhus.hrafnagud.munin.source.SourceService} and
 * {@link de.mhus.hrafnagud.munin.source.SourceMergePolicy}.
 */
@NullMarked
package de.mhus.hrafnagud.munin.sourcelist;

import org.jspecify.annotations.NullMarked;
