/**
 * Hugin — thought. Everything that hands text to a model and stores what comes
 * back.
 *
 * <p>The counterpart to {@link de.mhus.hrafnagud.munin}, which remembers: it
 * collects, deduplicates and stores, and it does so without a brain anywhere
 * near it. What interprets what Munin holds belongs here, and today that is
 * translation ({@code hugin.translate}) and deciding what a publisher's
 * category means ({@code hugin.classify}). Rating, clustering and summarising
 * would be neighbours in this package rather than anything inside Munin.
 *
 * <p>Two consequences, both load-bearing. Every package under here may import
 * from {@code munin}; none of them is imported by it — {@code ModuleBoundaryTest}
 * checks that, because deleting all of Hugin has to leave a collector that
 * still compiles. And everything here spends model time somebody pays for, so
 * its workers are off until an operator switches them on.
 *
 * <p>Configuration follows the same split: {@code hugin.*} in
 * {@link de.mhus.hrafnagud.config.HuginProperties} and as settings under the
 * same keys. See {@code specs/architecture.md} §2.
 */
@NullMarked
package de.mhus.hrafnagud.hugin;

import org.jspecify.annotations.NullMarked;
