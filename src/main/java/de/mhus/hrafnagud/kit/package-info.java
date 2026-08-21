/**
 * Serves this service's own kits, so a Vancetope project can configure itself
 * against the archive instead of being configured by hand.
 *
 * <p>Two kits, mirror images of each other: {@code translation} configures a
 * brain to <em>answer</em> this service, {@code hrafnagud-archive} configures a
 * project to <em>read</em> it.
 *
 * <p>Vancetope-facing, like {@code centauri}, {@code zarniwoop} and
 * {@code jaglan}: imports from {@code munin}, never the reverse, and deleting
 * it has to leave a compiling collector.
 */
@NullMarked
package de.mhus.hrafnagud.kit;

import org.jspecify.annotations.NullMarked;
