/**
 * The dimensions the archive can be filtered by, declared once for both
 * Vancetope-facing contracts.
 *
 * <p>Fifth package facing Vancetope, and the only one that answers neither
 * direction itself: {@code centauri} and {@code zarniwoop} both hand out the
 * same two facets, and a source that declares one filter twice will
 * eventually declare it differently twice. It imports {@code munin} and
 * {@code vance-ode-core}, is imported by those two, and is deleted with them —
 * so the boundary in {@code specs/architecture.md} §2.1 still holds.
 */
@NullMarked
package de.mhus.hrafnagud.facet;

import org.jspecify.annotations.NullMarked;
