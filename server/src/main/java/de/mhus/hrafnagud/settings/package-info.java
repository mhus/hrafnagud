/**
 * Settings: the operational values that can be changed while the service runs.
 *
 * <p>{@link de.mhus.hrafnagud.config} stays the layer that binds
 * {@code application.yml} and the environment; this package is the layer above
 * it, holding whatever an operator has since overridden in the database.
 *
 * <p>It serves both halves of the service — Munin's collecting and Hugin's
 * thinking — which is why it sits beside them rather than inside one of them. A consumer holds a {@link de.mhus.hrafnagud.settings.Setting}
 * handle and asks it for its value, so a change reaches it on the next read
 * without a restart and without anybody wiring a listener.
 *
 * <p>Design and the boundary to what stays a start-up property:
 * {@code specs/settings.md}.
 */
@NullMarked
package de.mhus.hrafnagud.settings;

import org.jspecify.annotations.NullMarked;
