package de.mhus.hrafnagud.munin.lang;

import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decides an article's language from up to three conflicting claims.
 *
 * <p>Precedence is operator override, then the feed's declaration, then
 * detection. The order encodes how much each can be trusted: a human who
 * configured a language on the source has looked at it, a feed's
 * {@code <language>} element is frequently a leftover from whatever
 * template the CMS shipped with, and detection is a guess — but a guess
 * from evidence, which is why it still beats nothing.
 *
 * <p>Detection deliberately does not override a declaration even when it
 * disagrees. Publishers of multilingual sites do sometimes tag a feed
 * correctly while individual entries quote at length in another language,
 * and second-guessing the publisher per entry would make the field
 * unstable in a way no consumer can reason about.
 */
@Service
@RequiredArgsConstructor
public class LanguageResolver {

    private final LanguageClassifier classifier;

    /**
     * The decided language and where it came from. {@code language} is
     * {@code null} exactly when {@code source} is
     * {@link LanguageSource#UNKNOWN}.
     */
    public record Resolution(@Nullable String language, LanguageSource source) {

        static Resolution unknown() {
            return new Resolution(null, LanguageSource.UNKNOWN);
        }
    }

    /**
     * @param sourceOverride language configured on the source, if any
     * @param declared       language declared by the feed or entry, if any
     * @param text           title plus teaser, used for detection
     */
    public Resolution resolve(@Nullable String sourceOverride, @Nullable String declared,
            String text) {

        String override = TextCleaner.normalizeLanguage(sourceOverride);
        if (override != null) {
            return new Resolution(override, LanguageSource.SOURCE);
        }

        String fromFeed = TextCleaner.normalizeLanguage(declared);
        if (fromFeed != null) {
            return new Resolution(fromFeed, LanguageSource.FEED);
        }

        if (StringUtils.isBlank(text)) {
            return Resolution.unknown();
        }
        String detected = TextCleaner.normalizeLanguage(classifier.detect(text));
        return detected == null
                ? Resolution.unknown()
                : new Resolution(detected, LanguageSource.DETECTED);
    }
}
