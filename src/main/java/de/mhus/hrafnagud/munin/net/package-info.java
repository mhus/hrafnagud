/**
 * Outbound HTTP: the single client every fetch goes through, per-host
 * pacing, and {@code robots.txt} evaluation.
 *
 * <p>No other package opens a connection. Centralising it is what makes the
 * politeness guarantees — one user agent, one rate limiter, one body cap —
 * actually hold rather than being a convention each worker reimplements.
 */
@NullMarked
package de.mhus.hrafnagud.munin.net;

import org.jspecify.annotations.NullMarked;
