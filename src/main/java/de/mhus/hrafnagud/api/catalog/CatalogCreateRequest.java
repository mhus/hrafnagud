package de.mhus.hrafnagud.api.catalog;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Registers a catalogue. Only {@code url} and {@code type} are required. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogCreateRequest {

    @NotBlank
    private String url = "";

    @NotBlank
    private String type = "";

    private @Nullable String name;

    private @Nullable String title;

    @Builder.Default
    private Map<String, String> params = new LinkedHashMap<>();

    private @Nullable Boolean enabled;

    @Builder.Default
    private List<String> include = new ArrayList<>();

    @Builder.Default
    private List<String> exclude = new ArrayList<>();

    private @Nullable Long refreshIntervalSeconds;

    private @Nullable Long listRefreshIntervalSeconds;

    /** Named interval class for everything this catalogue brings in. */
    private @Nullable String fetchProfile;

    private @Nullable Long sourceFetchIntervalSeconds;

    private @Nullable MissingListPolicy missingListPolicy;
}
