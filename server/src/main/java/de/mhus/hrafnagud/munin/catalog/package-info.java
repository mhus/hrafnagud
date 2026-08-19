/**
 * Catalogues: the layer that discovers source lists, so that the archive
 * fills itself instead of being filled.
 *
 * <p>Three layers, each reconciling the one below on its own schedule —
 * catalogue → source list → source. A reader per publication shape
 * ({@link de.mhus.hrafnagud.munin.catalog.CatalogReader}), never per
 * publisher.
 */
@NullMarked
package de.mhus.hrafnagud.munin.catalog;

import org.jspecify.annotations.NullMarked;
