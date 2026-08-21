package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which articles need translating, as two values and one question.
 *
 * <p>Named for what it holds rather than {@code TranslationPolicy}, which would
 * collide with {@code ArticleDocument.translationPolicy} — that field is the
 * filter's decision about an article, a different question with the same
 * obvious name.
 *
 * <p>The <b>pivot language</b> is what everything is normalised into. The
 * <b>readable languages</b> are the ones that need no normalising: an archive
 * whose reader is comfortable in English and German has nothing to gain from
 * translating an English article into German, even though German is the pivot.
 * Without that list the only language exempt from translation is the pivot
 * itself, which for a bilingual reader means paying for the half of the archive
 * they could already read.
 *
 * <p>The pivot is always readable — a model asked to translate German into
 * German can only return what it was given, at full price — so it does not have
 * to be repeated in the list.
 *
 * <p>Pure and immutable, so the decision can be tested without a database, and
 * built per use from the settings rather than captured: both values are
 * runtime-changeable.
 *
 * <p><b>Not a filter rule</b>, although the filter can match on language and
 * would technically do it. A denied article is one the archive decided is not
 * worth paying for, and it is served with {@code accepted:no} to a reader; an
 * article in a language the reader already understands is fully in scope and
 * merely needs no work. Expressing the second as the first would make the
 * reader-facing facet lie. See {@code specs/translation.md} §2a.
 */
public record TranslationLanguages(@Nullable String pivotLanguage, Set<String> readableLanguages) {

    /** No pivot language: nothing is ever queued, and the subsystem is dormant. */
    public static final TranslationLanguages DORMANT =
            new TranslationLanguages(null, Set.of());

    /**
     * Normalises what the settings hold.
     *
     * @param pivotLanguage the pivot, as configured; blank or unparseable
     *                      leaves the subsystem dormant
     * @param readable      languages needing no translation, already normalised
     *                      by {@code SettingLanguages}; normalised again here
     *                      because this class must not depend on where its
     *                      inputs came from
     */
    public static TranslationLanguages of(@Nullable String pivotLanguage,
            Collection<String> readable) {
        String pivot = TextCleaner.normalizeLanguage(pivotLanguage);
        if (pivot == null) {
            return DORMANT;
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : readable) {
            String normalised = TextCleaner.normalizeLanguage(tag);
            if (normalised != null) {
                tags.add(normalised);
            }
        }
        return new TranslationLanguages(pivot, Set.copyOf(tags));
    }

    /**
     * Whether an article in this language has to be translated.
     *
     * <p>An <em>unknown</em> language is translated. Without knowing what it is
     * we cannot rule it out, and a provider handed text that is already in the
     * target returns it unchanged; the cost of being wrong that way is one
     * call, while skipping wrongly loses the translation silently.
     */
    public boolean needsTranslation(@Nullable String articleLanguage) {
        if (pivotLanguage == null) {
            return false;
        }
        if (articleLanguage == null) {
            return true;
        }
        return !pivotLanguage.equals(articleLanguage)
                && !readableLanguages.contains(articleLanguage);
    }
}
