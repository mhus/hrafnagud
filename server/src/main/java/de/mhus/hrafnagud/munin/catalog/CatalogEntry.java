package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.source.SourceListType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One source list a catalogue offers.
 *
 * <p><b>A URL and a label, nothing more.</b> The catalogue layer enumerates
 * documents; it never reads them. That is what keeps a refresh of a 66-entry
 * catalogue down to two HTTP calls instead of sixty-eight — the lists are
 * fetched by the layer that owns them, on their own schedule.
 *
 * @param key        stable identifier within the catalogue, and what the
 *                   include/exclude globs match against. For a directory of
 *                   files that is the path ({@code countries/Germany.opml}),
 *                   which is why the globs read like paths.
 * @param url        where the list document lives.
 * @param title      display label, from the directory's own text.
 * @param type       how the list document is to be parsed.
 * @param country    ISO 3166-1 alpha-2, when the catalogue groups by country.
 * @param categories categories to apply to every source of that list.
 */
public record CatalogEntry(
        String key,
        String url,
        String title,
        SourceListType type,
        @Nullable String country,
        List<String> categories) {

    public CatalogEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        key = key.trim();
        url = url.trim();
        title = title == null || title.isBlank() ? key : title.trim();
        type = type == null ? SourceListType.OPML : type;
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static CatalogEntry of(String key, String url, String title) {
        return new CatalogEntry(key, url, title, SourceListType.OPML, null, List.of());
    }
}
