/**
 * Turning Munin's translation backlog into stored translations.
 *
 * <p>Split from Munin so the archive keeps no dependency on Vancetope.
 * Collecting, deduplicating and querying news must work without a brain;
 * translation is the one part that reaches outside, and putting it in its
 * own module is what keeps that boundary visible rather than notional.
 *
 * <p>Which service does the translating is a
 * {@link de.mhus.hrafnagud.translate.TranslationProvider} — one
 * implementation ships (via Vancetope events), and a direct model call or
 * a machine-translation API would be another. The queue does not know
 * which it is talking to.
 */
@NullMarked
package de.mhus.hrafnagud.translate;

import org.jspecify.annotations.NullMarked;
