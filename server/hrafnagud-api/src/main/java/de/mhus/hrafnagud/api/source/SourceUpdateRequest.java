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
 * Body of {@code PUT /api/v1/sources/{name}} — a sparse patch.
 *
 * <p>Every field is optional and {@code null} means "leave unchanged". To
 * <em>clear</em> a string field, send an empty string; to clear the
 * category list, send an empty array. Absent and null-valued are
 * indistinguishable over JSON, so a sentinel is unavoidable and an empty
 * string is the least surprising one for these fields (none of them has a
 * meaningful empty value).
 *
 * <p>Every field actually present here is added to the source's
 * {@code lockedFields}, which stops a later refresh of the owning source
 * list from reverting the edit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceUpdateRequest {

    @Size(max = 500)
    private @Nullable String title;

    /**
     * New feed URL. Changing it re-identifies the source, so the normalised
     * form must not collide with another source.
     */
    @Size(max = 2000)
    private @Nullable String url;

    @Size(max = 2000)
    private @Nullable String siteUrl;

    private @Nullable Boolean enabled;

    @Size(max = 16)
    private @Nullable String language;

    @Size(max = 2)
    private @Nullable String country;

    private @Nullable List<String> categories;

    /**
     * Pins the poll interval to this value. The adaptive logic keeps
     * running from here, so this is a starting point rather than a
     * permanent freeze.
     */
    private @Nullable Long fetchIntervalSeconds;
}
