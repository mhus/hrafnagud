package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterReevaluationReport;
import de.mhus.hrafnagud.api.filter.FilterRuleDto;
import de.mhus.hrafnagud.api.filter.FilterRuleRequest;
import de.mhus.hrafnagud.munin.filter.FilterReevaluationService;
import de.mhus.hrafnagud.munin.filter.FilterRuleDocument;
import de.mhus.hrafnagud.munin.filter.FilterRuleService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules, and the button that applies them to what is already stored.
 *
 * <p>Full CRUD, unlike most of this API's surface: a rule is cheap to write and
 * cheap to undo, and the enable switch means the destructive option is rarely
 * the needed one. What is deliberately absent is a rename — an article records
 * the name of the rule that decided it, so the name is identity rather than
 * decoration.
 */
@RestController
@RequestMapping("/api/v1/filter")
@RequiredArgsConstructor
public class FilterController {

    private final FilterRuleService rules;
    private final FilterReevaluationService reevaluation;

    @GetMapping("/rules")
    public List<FilterRuleDto> list(
            @RequestParam(value = "pipeline", required = false) @Nullable FilterPipeline pipeline) {
        return rules.list(pipeline).stream().map(FilterController::toDto).toList();
    }

    @GetMapping("/rules/{name}")
    public FilterRuleDto get(@PathVariable("name") String name) {
        return toDto(rules.require(name));
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public FilterRuleDto create(@Valid @RequestBody FilterRuleRequest request) {
        return toDto(rules.create(request));
    }

    @PutMapping("/rules/{name}")
    public FilterRuleDto update(@PathVariable("name") String name,
            @Valid @RequestBody FilterRuleRequest request) {
        return toDto(rules.update(name, request));
    }

    /**
     * Switches a rule on or off.
     *
     * <p>Its own endpoint rather than a field on the update, because this is the
     * operation an operator actually performs while working on a rule set —
     * turning something off to see what it was doing — and it should not require
     * resending the whole rule.
     */
    @PostMapping("/rules/{name}/enabled")
    public FilterRuleDto setEnabled(@PathVariable("name") String name,
            @RequestParam("value") boolean value) {
        return toDto(rules.setEnabled(name, value));
    }

    @DeleteMapping("/rules/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("name") String name) {
        rules.delete(name);
    }

    /**
     * Applies the current rules to articles already stored.
     *
     * <p>{@code days} is the window and the normal way to use this: rules
     * change, and re-deciding the last week is a different operation from
     * re-deciding a five-year archive. Omitting it means the whole archive,
     * which is allowed and still bounded by the cap.
     */
    @PostMapping("/reevaluate")
    public FilterReevaluationReport reevaluate(
            @RequestParam(value = "days", required = false) @Nullable Integer days,
            @RequestParam(value = "max", required = false) @Nullable Integer max) {

        Instant since = days == null || days <= 0
                ? null
                : Instant.now().minus(Duration.ofDays(days));
        return reevaluation.reevaluate(since, max);
    }

    private static FilterRuleDto toDto(FilterRuleDocument rule) {
        return FilterRuleDto.builder()
                .name(rule.getName())
                .pipeline(rule.getPipeline())
                .decision(rule.getDecision())
                .type(rule.getType())
                .matchType(rule.getMatchType())
                .value(rule.getValue())
                .enabled(rule.isEnabled())
                .note(rule.getNote())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }
}
