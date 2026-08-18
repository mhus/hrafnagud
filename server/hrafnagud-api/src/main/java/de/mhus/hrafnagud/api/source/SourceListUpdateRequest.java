package de.mhus.hrafnagud.api.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /api/v1/source-lists/{name}} — a sparse patch with the
 * same "null means unchanged, empty string clears" convention as
 * {@link SourceUpdateRequest}.
 *
 * <p>Changing a default here does not retroactively rewrite sources the
 * list already imported; defaults apply at import time. The next refresh
 * picks them up for every source whose corresponding field is not locked.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceListUpdateRequest {

    @Size(max = 500)
    private @Nullable String title;

    @Size(max = 2000)
    private @Nullable String url;

    private @Nullable Boolean enabled;

    @Size(max = 16)
    private @Nullable String defaultLanguage;

    @Size(max = 2)
    private @Nullable String defaultCountry;

    private @Nullable List<String> defaultCategories;

    private @Nullable Long defaultFetchIntervalSeconds;

    private @Nullable MissingSourcePolicy missingSourcePolicy;

    private @Nullable Long refreshIntervalSeconds;
}
