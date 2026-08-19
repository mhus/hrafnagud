/**
 * Domain exceptions and the single advice that turns them into HTTP status
 * codes.
 *
 * <p>Services throw these rather than {@code ResponseStatusException} so
 * that they stay callable from the background workers, which have no
 * response to attach a status to.
 */
@NullMarked
package de.mhus.hrafnagud.munin.error;

import org.jspecify.annotations.NullMarked;
