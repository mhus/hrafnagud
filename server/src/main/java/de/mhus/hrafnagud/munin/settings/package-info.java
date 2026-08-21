/**
 * Settings: the operational values that can be changed while the service runs.
 *
 * <p>{@link de.mhus.hrafnagud.munin.config.MuninProperties} stays the layer
 * that binds {@code application.yml} and the environment; this package is the
 * layer above it, holding whatever an operator has since overridden in the
 * database. A consumer holds a {@link de.mhus.hrafnagud.munin.settings.Setting}
 * handle and asks it for its value, so a change reaches it on the next read
 * without a restart and without anybody wiring a listener.
 *
 * <p>Design and the boundary to what stays a start-up property:
 * {@code specs/settings.md}.
 */
@NullMarked
package de.mhus.hrafnagud.munin.settings;

import org.jspecify.annotations.NullMarked;
