package de.mhus.hrafnagud.api.filter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Creates or replaces a rule.
 *
 * <p>{@code matchType} and {@code enabled} are the only optional fields:
 * everything else is a choice the rule cannot be written without. A default
 * pipeline or a default decision would silently pick one of the two answers
 * that matter most.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterRuleRequest {

    /** Left empty on create, a name is generated from type and value. */
    private @Nullable String name;

    @NotNull
    private FilterPipeline pipeline = FilterPipeline.TRANSLATION;

    @NotNull
    private FilterDecision decision = FilterDecision.DENY;

    @NotNull
    private FilterRuleType type = FilterRuleType.HOST;

    private @Nullable FilterMatchType matchType;

    @NotBlank
    private String value = "";

    private @Nullable Boolean enabled;

    private @Nullable String note;
}
