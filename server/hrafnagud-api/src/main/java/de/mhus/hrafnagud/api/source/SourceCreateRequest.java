package de.mhus.hrafnagud.api.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/sources}.
 *
 * <p>Only {@code url} is required: a feed knows its own title, and the
 * technical {@code name} is derived from the URL when omitted so that
 * adding a source is a one-field call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceCreateRequest {

    /**
     * Feed URL. Normalised before use, and the normalised form is the
     * identity — posting the same feed twice with different tracking
     * parameters is a conflict, not two sources.
     */
    @NotBlank
    private String url = "";

    /**
     * Technical key. Derived from the URL's host plus a short hash of the
     * full URL when omitted, which keeps it stable across title changes.
     */
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,127}$",
            message = "name must be lowercase alphanumeric with . _ -")
    private @Nullable String name;

    @Size(max = 500)
    private @Nullable String title;

    private @Nullable SourceType type;

    @Size(max = 2000)
    private @Nullable String siteUrl;

    /** Defaults to {@code true}. */
    private @Nullable Boolean enabled;

    /** BCP-47 primary subtag. Overrides feed declaration and detection. */
    @Size(max = 16)
    private @Nullable String language;

    @Size(max = 2)
    private @Nullable String country;

    private List<String> categories = new ArrayList<>();

    /** Initial poll interval. Defaults to the configured server default. */
    private @Nullable Long fetchIntervalSeconds;
}
