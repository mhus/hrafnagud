/**
 * Serving the archive to Vancetope as a Centauri feed source.
 *
 * <p>The mirror image of {@link de.mhus.hrafnagud.translate}: that package
 * calls out to a brain, this one answers a brain's calls. Both live outside
 * Munin for the same reason — the archive collects, deduplicates and
 * queries without knowing a brain exists, and every module that faces one
 * stays on this side of that line.
 *
 * <p>The REST surface is not written here. A
 * {@link de.mhus.vance.ode.centauri.FeedSource} bean is all the Ode module
 * needs to start serving the contract, so this package is one
 * implementation of that interface, a mapper and a cursor.
 */
@NullMarked
package de.mhus.hrafnagud.centauri;

import org.jspecify.annotations.NullMarked;
