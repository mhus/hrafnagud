package de.mhus.hrafnagud.munin.filter;

import de.mhus.hrafnagud.api.filter.FilterMatchType;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterRuleRequest;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.ConflictException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code filter_rules} collection.
 *
 * <p>Every write ends with the registry reloaded, so a saved rule is in effect
 * for the next article rather than after an interval. That coupling is
 * deliberate and is why the two classes are separate: this one is the record,
 * {@link FilterRuleRegistry} is the working set, and nothing else may write
 * either.
 */
@Service
@Slf4j
public class FilterRuleService {

    private final FilterRuleRepository repository;
    private final FilterRuleRegistry registry;

    public FilterRuleService(FilterRuleRepository repository, FilterRuleRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    // ─── Read ───

    public List<FilterRuleDocument> list(@Nullable FilterPipeline pipeline) {
        List<FilterRuleDocument> all = repository.findAll(
                Sort.by("pipeline", "decision", "type", "value"));
        return pipeline == null
                ? all
                : all.stream().filter(rule -> rule.getPipeline() == pipeline).toList();
    }

    public Optional<FilterRuleDocument> find(String name) {
        return repository.findByName(name);
    }

    public FilterRuleDocument require(String name) {
        return find(name).orElseThrow(() -> new NotFoundException("filter rule", name));
    }

    // ─── Write ───

    public FilterRuleDocument create(FilterRuleRequest request) {
        Instant now = Instant.now();
        FilterMatchType matchType = matchTypeOf(request);
        String value = request.getValue().trim();
        validate(matchType, value);

        String name = request.getName() == null || request.getName().isBlank()
                ? generateName(request, value)
                : request.getName().trim();
        if (repository.findByName(name).isPresent()) {
            throw new ConflictException("filter rule '" + name + "' already exists");
        }

        FilterRuleDocument document = FilterRuleDocument.builder()
                .name(name)
                .pipeline(request.getPipeline())
                .decision(request.getDecision())
                .type(request.getType())
                .matchType(matchType)
                .value(value)
                // Enabled unless told otherwise: a rule written and then not in
                // effect is a surprise, and the console offers the switch right
                // next to it.
                .enabled(request.getEnabled() == null || request.getEnabled())
                .note(request.getNote())
                .createdAt(now)
                .updatedAt(now)
                .build();

        FilterRuleDocument saved = repository.save(document);
        registry.reload();
        log.info("Filter rule '{}' created: {} {} {} {} '{}'", saved.getName(),
                saved.getPipeline(), saved.getDecision(), saved.getType(),
                saved.getMatchType(), saved.getValue());
        return saved;
    }

    /** Replaces everything but the name, which is the identity. */
    public FilterRuleDocument update(String name, FilterRuleRequest request) {
        FilterRuleDocument existing = require(name);
        FilterMatchType matchType = matchTypeOf(request);
        String value = request.getValue().trim();
        validate(matchType, value);

        existing.setPipeline(request.getPipeline());
        existing.setDecision(request.getDecision());
        existing.setType(request.getType());
        existing.setMatchType(matchType);
        existing.setValue(value);
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled());
        }
        existing.setNote(request.getNote());
        existing.setUpdatedAt(Instant.now());

        FilterRuleDocument saved = repository.save(existing);
        registry.reload();
        return saved;
    }

    /** Switches one rule on or off without losing what it said. */
    public FilterRuleDocument setEnabled(String name, boolean enabled) {
        FilterRuleDocument rule = require(name);
        rule.setEnabled(enabled);
        rule.setUpdatedAt(Instant.now());
        FilterRuleDocument saved = repository.save(rule);
        registry.reload();
        log.info("Filter rule '{}' {}", name, enabled ? "enabled" : "disabled");
        return saved;
    }

    public void delete(String name) {
        repository.delete(require(name));
        registry.reload();
        log.info("Filter rule '{}' deleted", name);
        // Articles keep the name of a deleted rule in their policy field. That
        // is on purpose: it is the record of why something was skipped, and
        // blanking it would make the history less true, not tidier.
    }

    // ─── Helpers ───

    private static FilterMatchType matchTypeOf(FilterRuleRequest request) {
        return request.getMatchType() == null ? FilterMatchType.EXACT : request.getMatchType();
    }

    /**
     * Rejects a regex that does not compile, at write time.
     *
     * <p>This is the whole reason the check exists here rather than in the
     * evaluator: an unparseable pattern discovered while filtering an article
     * would be a rule that quietly matches nothing — or throws once per
     * article — and the operator would have no way to see which rule is broken.
     * Here it comes back as a 400 with the syntax error in it.
     */
    private static void validate(FilterMatchType matchType, String value) {
        if (value.isBlank()) {
            throw new BadRequestException("rule value must not be blank");
        }
        if (matchType != FilterMatchType.REGEX) {
            return;
        }
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new BadRequestException("invalid regular expression: " + e.getMessage());
        }
    }

    /**
     * A readable name from what the rule says, e.g. {@code deny-host-youtube-com}.
     *
     * <p>Generated rather than required, because the name matters to the article
     * that records it and not to the person writing the rule — but a generated
     * name still has to be legible in that field six months later, which rules
     * out a hash or a counter.
     */
    private String generateName(FilterRuleRequest request, String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-$", "");
        }
        String base = "%s-%s-%s".formatted(
                request.getDecision().name().toLowerCase(Locale.ROOT),
                request.getType().name().toLowerCase(Locale.ROOT),
                slug.isEmpty() ? "rule" : slug);

        String candidate = base;
        for (int suffix = 2; repository.findByName(candidate).isPresent(); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }
}
