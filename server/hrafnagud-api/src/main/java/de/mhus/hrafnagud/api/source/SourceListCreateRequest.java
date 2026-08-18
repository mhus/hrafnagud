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

/** Body of {@code POST /api/v1/source-lists}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceListCreateRequest {

    @NotBlank
    private String url = "";

    /** Derived from the URL when omitted, like {@code SourceCreateRequest}. */
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,127}$",
            message = "name must be lowercase alphanumeric with . _ -")
    private @Nullable String name;

    @Size(max = 500)
    private @Nullable String title;

    /** Defaults to {@link SourceListType#OPML}. */
    private @Nullable SourceListType type;

    private @Nullable Boolean enabled;

    @Size(max = 16)
    private @Nullable String defaultLanguage;

    @Size(max = 2)
    private @Nullable String defaultCountry;

    private List<String> defaultCategories = new ArrayList<>();

    private @Nullable Long defaultFetchIntervalSeconds;

    /** Defaults to {@link MissingSourcePolicy#DISABLE}. */
    private @Nullable MissingSourcePolicy missingSourcePolicy;

    private @Nullable Long refreshIntervalSeconds;
}
