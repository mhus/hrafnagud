package de.mhus.hrafnagud.munin.catalog;

import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Which entries of a catalogue this installation actually wants.
 *
 * <p>Globs over the entry key, which for a directory of files is its path —
 * {@code countries/*}, {@code recommended/Programming.opml}. Glob and not
 * regex: the thing being matched is a path, the people writing the filter are
 * thinking in paths, and a stray {@code .} in a regex silently matching any
 * character is a trap that only shows up as too many feeds.
 *
 * <p>Empty include means everything. That is the honest reading of an unset
 * filter, and the alternative — empty means nothing — would make a catalogue
 * that somebody registered and then forgot to configure look broken rather
 * than eager.
 */
public final class CatalogEntryFilter {

    private final List<Pattern> include;
    private final List<Pattern> exclude;

    public CatalogEntryFilter(List<String> include, List<String> exclude) {
        this.include = compile(include);
        this.exclude = compile(exclude);
    }

    public static CatalogEntryFilter of(SourceCatalogDocument catalog) {
        return new CatalogEntryFilter(catalog.getInclude(), catalog.getExclude());
    }

    /** Exclude wins: a veto is easier to reason about than a precedence rule. */
    public boolean accepts(String key) {
        if (matchesAny(exclude, key)) {
            return false;
        }
        return include.isEmpty() || matchesAny(include, key);
    }

    private static boolean matchesAny(List<Pattern> patterns, String key) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compile(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        return globs.stream()
                .filter(StringUtils::isNotBlank)
                .map(glob -> Pattern.compile(toRegex(glob.trim()), Pattern.CASE_INSENSITIVE))
                .toList();
    }

    /**
     * Glob → regex, by hand rather than through {@code FileSystems.getDefault()
     * .getPathMatcher("glob:…")}: that one is filesystem-flavoured (on Windows
     * the separator changes what {@code *} crosses), and these keys are not
     * filesystem paths. Supported: {@code *} within a segment, {@code **}
     * across segments, {@code ?} for one character. Everything else is literal.
     */
    static String toRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        out.append(".*");
                        i++;
                    } else {
                        out.append("[^/]*");
                    }
                }
                case '?' -> out.append("[^/]");
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.append('$').toString();
    }
}
