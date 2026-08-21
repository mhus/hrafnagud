package de.mhus.hrafnagud.munin.category;

import de.mhus.hrafnagud.api.category.CategoryMappingStatus;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Stage two: decide one category that string matching could not settle.
 *
 * <p>An interface, and Munin holds only the interface — the implementation
 * that calls a Vancetope brain lives outside, in
 * {@code de.mhus.hrafnagud.hugin.classify}, for the same reason translation does:
 * the archive has to be collectable and classifiable-by-hand with no brain
 * anywhere near it (specs/architecture.md §2.1).
 *
 * <p>With no implementation wired, mappings accumulate as {@code NEW} and
 * {@code GUESSED}. That is a legible state rather than a broken one — stage
 * one still resolves the frequent head — and the startup log says so.
 */
public interface CategoryResolver {

    /** Name of the wired resolver, for the log and the mapping's provenance. */
    String name();

    /**
     * Decide one category.
     *
     * @param raw       what the publisher wrote
     * @param candidate what stage one guessed, or null — offered so the model
     *                  can agree cheaply rather than start from nothing
     * @return the decision; {@code NOT_A_TOPIC} and {@code IS_PLACE} are
     *         answers, not failures
     * @throws RuntimeException when the call itself failed; the caller records
     *         an attempt and retries later, which is a different thing from
     *         "this is not a topic"
     */
    Decision resolve(String raw, @Nullable String candidate);

    /**
     * @param status  one of {@code RESOLVED}, {@code NOT_A_TOPIC}, {@code IS_PLACE}
     * @param topicId Media Topic qcode when resolved, else null. Verified
     *                against the vocabulary by the caller — a model inventing
     *                a code must not reach the database.
     * @param note    one sentence of reasoning, kept for whoever reviews it
     */
    record Decision(CategoryMappingStatus status, @Nullable String topicId,
            @Nullable String note) {

        public static Decision topic(String topicId, @Nullable String note) {
            return new Decision(CategoryMappingStatus.RESOLVED, topicId, note);
        }

        public static Decision notATopic(@Nullable String note) {
            return new Decision(CategoryMappingStatus.NOT_A_TOPIC, null, note);
        }

        public static Decision place(@Nullable String note) {
            return new Decision(CategoryMappingStatus.IS_PLACE, null, note);
        }
    }
}
