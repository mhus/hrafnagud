/**
 * REST surface and the mappers between documents and DTOs.
 *
 * <p>Controllers validate, map and delegate. No business logic, no
 * repository access — the services own their collections, and a controller
 * that reached past them would be the first crack in that.
 */
@NullMarked
package de.mhus.hrafnagud.munin.web;

import org.jspecify.annotations.NullMarked;
