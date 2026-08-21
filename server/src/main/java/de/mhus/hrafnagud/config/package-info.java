/**
 * Typed runtime configuration, one class per property root.
 *
 * <p>{@link de.mhus.hrafnagud.config.MuninProperties} binds {@code munin.*} —
 * collecting and storing. {@link de.mhus.hrafnagud.config.HuginProperties}
 * binds {@code hugin.*} — everything that hands text to a model.
 * {@link de.mhus.hrafnagud.config.HrafnagudProperties} binds
 * {@code hrafnagud.*}, what belongs to neither half.
 *
 * <p>Outside {@code munin} on purpose: configuration serves both halves, so a
 * package under one of them would be claiming ownership it does not have. And
 * these are the <em>default</em> layer — what is actually in force comes from
 * {@link de.mhus.hrafnagud.settings.Settings}.
 */
@NullMarked
package de.mhus.hrafnagud.config;

import org.jspecify.annotations.NullMarked;
