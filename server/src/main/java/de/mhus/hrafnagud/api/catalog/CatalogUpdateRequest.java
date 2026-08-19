package de.mhus.hrafnagud.api.catalog;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Changes a catalogue. Every field is nullable and null means "leave alone" —
 * an absent field must not read as "set to empty", or a request that only
 * changes the interval would silently clear the filter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogUpdateRequest {

    private @Nullable String title;

    private @Nullable String url;

    private @Nullable Map<String, String> params;

    private @Nullable Boolean enabled;

    private @Nullable List<String> include;

    private @Nullable List<String> exclude;

    private @Nullable Long refreshIntervalSeconds;

    private @Nullable Long listRefreshIntervalSeconds;

    private @Nullable String fetchProfile;

    private @Nullable Long sourceFetchIntervalSeconds;

    private @Nullable MissingListPolicy missingListPolicy;
}
