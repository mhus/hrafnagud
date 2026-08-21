package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A set of language tags, written the way an operator would write it:
 * {@code en,de}.
 *
 * <p>Entries are normalised to their BCP-47 primary subtag, lowercase — the
 * same normalisation an article's own language goes through, because a set that
 * held {@code EN} while articles carry {@code en} would silently never match.
 * An entry that is not a language tag is <b>refused</b> rather than dropped:
 * a typo in this setting has no visible effect other than a translation bill,
 * which is the worst way to find out about it.
 */
public final class SettingLanguages extends Setting<Set<String>> {

    private static final Logger log = LoggerFactory.getLogger(SettingLanguages.class);

    SettingLanguages(SettingsService store, String key, Supplier<Set<String>> defaultValue,
            String description) {
        super(store, key, SettingType.STRING_LIST, defaultValue, description);
    }

    @Override
    protected Set<String> parse(String raw) {
        return parseTags(raw, true);
    }

    @Override
    protected String format(Set<String> value) {
        return String.join(",", value);
    }

    /**
     * Reads a comma-separated list of language tags.
     *
     * @param strict {@code true} to throw on an entry that is not a language
     *               tag — used on the write path, where somebody is watching
     *               and gets a 400. The configuration layer reads it
     *               leniently instead: a file cannot be handed an error
     *               message, so a bad entry is dropped and named in the log.
     */
    static Set<String> parseTags(String raw, boolean strict) {
        Set<String> tags = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String tag = TextCleaner.normalizeLanguage(part);
            if (tag == null) {
                if (strict) {
                    throw new IllegalArgumentException(
                            "not a language tag: '" + part.trim() + "'");
                }
                log.warn("Ignoring '{}': not a language tag", part.trim());
                continue;
            }
            tags.add(tag);
        }
        // Insertion order, not Set.copyOf: the order is what the operator
        // typed, and format() has to hand back the same string it showed.
        return Collections.unmodifiableSet(tags);
    }

    /** The same, for a list bound from {@code application.yml}. */
    static Set<String> normalise(Collection<String> configured) {
        return parseTags(String.join(",", configured), false);
    }
}
