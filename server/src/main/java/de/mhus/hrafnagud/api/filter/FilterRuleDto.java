package de.mhus.hrafnagud.api.filter;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One filter rule. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilterRuleDto {

    /** Technical identity, and what an article records as the deciding rule. */
    private String name = "";

    private FilterPipeline pipeline = FilterPipeline.TRANSLATION;

    private FilterDecision decision = FilterDecision.DENY;

    private FilterRuleType type = FilterRuleType.HOST;

    private FilterMatchType matchType = FilterMatchType.EXACT;

    private String value = "";

    /**
     * A disabled rule is kept and ignored. Switching one off to see what it was
     * doing is the normal way to work on a rule set; deleting it to find out is
     * not.
     */
    private boolean enabled = true;

    /** Why this rule exists — for whoever reads it in six months. */
    private @Nullable String note;

    private @Nullable Instant createdAt;

    private @Nullable Instant updatedAt;
}
