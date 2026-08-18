package de.mhus.hrafnagud.munin.source;

import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One feed as a source list described it, after URL normalisation and
 * before it meets the registry.
 *
 * <p>Separate from {@link SourceDocument} because a candidate carries only
 * what a list can know. Everything else on a source — poll interval, failure
 * history, validators, statistics — belongs to us, and a type that could
 * express those fields would invite a list to overwrite them.
 */
@Value
@Builder
public class SourceCandidate {

    /** Normalised feed URL. Identity. */
    String url;

    /** Label from the list document; may be empty. */
    String title;

    /** Publisher home page, when the list declared one. */
    @Nullable String siteUrl;

    /**
     * Categories the list assigned, typically from the enclosing OPML
     * outline. Verbatim — the list's vocabulary is not translated into ours.
     */
    @Singular
    List<String> categories;

    /** Language the list declared for this feed, if any. */
    @Nullable String language;

    @Nullable String country;
}
