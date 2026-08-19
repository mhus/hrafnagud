package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.munin.source.SourceCandidate;
import de.mhus.hrafnagud.munin.util.Slugs;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Parses a plain-text list: one feed URL per line.
 *
 * <p>The format a list kept in a git repository actually wants to be. Two
 * conveniences beyond bare URLs, both optional:
 *
 * <ul>
 *   <li>{@code #} starts a comment, and a comment on its own line that
 *       reads {@code # category: Politics} sets the category for every
 *       following entry until the next such line;</li>
 *   <li>a URL may be followed by whitespace and a title.</li>
 * </ul>
 *
 * <p>Anything else on a line makes it a reject with a warning rather than a
 * parse failure.
 */
@Component
public class TextSourceListParser implements SourceListParser {

    private static final int MAX_WARNINGS = 20;

    private static final String CATEGORY_DIRECTIVE = "category:";

    @Override
    public SourceListType type() {
        return SourceListType.TEXT;
    }

    @Override
    public ParsedSourceList parse(String body, int maxEntries) {
        ParsedSourceList.ParsedSourceListBuilder result = ParsedSourceList.builder();
        List<String> warnings = new ArrayList<>();
        List<SourceCandidate> entries = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        List<String> currentCategories = new ArrayList<>();
        int invalid = 0;
        int lineNumber = 0;

        for (String rawLine : body.split("\\R")) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String comment = line.substring(1).trim();
                if (StringUtils.startsWithIgnoreCase(comment, CATEGORY_DIRECTIVE)) {
                    String category = comment.substring(CATEGORY_DIRECTIVE.length()).trim();
                    currentCategories = category.isEmpty() ? List.of() : List.of(category);
                }
                continue;
            }
            if (entries.size() >= maxEntries) {
                break;
            }

            String[] parts = line.split("\\s+", 2);
            Optional<String> url = UrlNormalizer.normalize(parts[0]);
            if (url.isEmpty()) {
                invalid++;
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("line " + lineNumber + ": unusable url "
                            + StringUtils.abbreviate(parts[0], 120));
                }
                continue;
            }
            if (!seenUrls.add(url.get())) {
                continue;
            }

            entries.add(SourceCandidate.builder()
                    .url(url.get())
                    .title(parts.length > 1
                            ? StringUtils.abbreviate(parts[1].trim(), 500)
                            : Slugs.hostOf(url.get()))
                    .categories(currentCategories)
                    .build());
        }

        entries.forEach(result::entry);
        warnings.forEach(result::warning);
        return result.invalidCount(invalid).build();
    }
}
