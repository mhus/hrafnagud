package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.munin.source.SourceCandidate;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Result of parsing a source-list document.
 *
 * <p>Carries warnings alongside the entries rather than failing on the
 * first bad one. Directories are community-maintained and always contain
 * some proportion of dead links, {@code javascript:} placeholders and
 * duplicate entries; refusing the whole document over three bad rows out of
 * a thousand would make the feature useless.
 */
@Value
@Builder
public class ParsedSourceList {

    /** Title declared by the document, when it has one. */
    @Nullable String title;

    /** Usable entries, already URL-normalised and deduplicated. */
    @Singular
    List<SourceCandidate> entries;

    /** Entries that were rejected, one human-readable line each. */
    @Singular
    List<String> warnings;

    /** Number of entries rejected — {@code warnings} is capped, this is not. */
    int invalidCount;
}
