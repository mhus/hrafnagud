package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.api.source.SourceListType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One list as a catalogue offered it, in the vocabulary of the layer that
 * stores it.
 *
 * <p>Mirrors {@code SourceCandidate}, which is what a list offers to the
 * source layer, and exists for the same reason: the storing layer defines
 * what it accepts. Without it {@code SourceListService} would import the
 * catalogue package's types, and the dependency between the two layers would
 * point both ways.
 */
public record SourceListCandidate(
        String url,
        String title,
        SourceListType type,
        @Nullable String country,
        List<String> categories,
        @Nullable Long refreshIntervalSeconds,
        @Nullable String fetchProfile,
        @Nullable Long fetchIntervalSeconds) {

    public SourceListCandidate {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        url = url.trim();
        title = title == null ? "" : title.trim();
        type = type == null ? SourceListType.OPML : type;
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
