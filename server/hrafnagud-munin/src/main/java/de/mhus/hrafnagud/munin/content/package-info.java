/**
 * Full-article fetching: retrieving the publisher's page behind a feed
 * entry and extracting the prose from it.
 *
 * <p>Kept apart from feed ingest for three reasons that all point the same
 * way. It is an order of magnitude slower, it fails in far more ways
 * (paywalls, consent walls, robots rules, hostile markup), and it is a
 * qualitatively different act — reading a document published for polling
 * versus fetching a page that was not. So it has its own queue, its own
 * state machine on each article, and its own master switch, which is off by
 * default.
 */
@NullMarked
package de.mhus.hrafnagud.munin.content;

import org.jspecify.annotations.NullMarked;
