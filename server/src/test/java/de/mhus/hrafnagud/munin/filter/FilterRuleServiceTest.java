package de.mhus.hrafnagud.munin.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.filter.FilterDecision;
import de.mhus.hrafnagud.api.filter.FilterMatchType;
import de.mhus.hrafnagud.api.filter.FilterPipeline;
import de.mhus.hrafnagud.api.filter.FilterRuleRequest;
import de.mhus.hrafnagud.api.filter.FilterRuleType;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.ConflictException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Writing rules: what is rejected, and what a rule ends up called. */
class FilterRuleServiceTest {

    private FilterRuleRepository repository;
    private FilterRuleService service;

    @BeforeEach
    void setUp() {
        repository = mock(FilterRuleRepository.class);
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrue()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new FilterRuleService(repository, new FilterRuleRegistry(repository));
    }

    private static FilterRuleRequest.FilterRuleRequestBuilder request() {
        return FilterRuleRequest.builder()
                .pipeline(FilterPipeline.TRANSLATION)
                .decision(FilterDecision.DENY)
                .type(FilterRuleType.HOST)
                .value("youtube.com");
    }

    /**
     * The one rejection that matters. Discovering an unparseable pattern while
     * filtering an article would give the operator a rule that silently matches
     * nothing and no way to tell which one is broken.
     */
    @Test
    void an_invalid_regular_expression_is_rejected_when_written() {
        FilterRuleRequest broken = request()
                .matchType(FilterMatchType.REGEX)
                .value("[unclosed")
                .build();

        assertThatThrownBy(() -> service.create(broken))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid regular expression");
    }

    @Test
    void a_valid_regular_expression_is_accepted() {
        FilterRuleDocument saved = service.create(request()
                .type(FilterRuleType.URL)
                .matchType(FilterMatchType.REGEX)
                .value("/(amp|sponsored)/")
                .build());

        assertThat(saved.getMatchType()).isEqualTo(FilterMatchType.REGEX);
    }

    @Test
    void a_blank_value_is_rejected() {
        assertThatThrownBy(() -> service.create(request().value("   ").build()))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * The generated name has to stay legible, because it is what an article
     * records as the reason it was skipped — a hash or a counter would make the
     * article's own explanation useless.
     */
    @Test
    void a_generated_name_says_what_the_rule_does() {
        FilterRuleDocument saved = service.create(request().build());

        assertThat(saved.getName()).isEqualTo("deny-host-youtube-com");
    }

    @Test
    void an_explicit_name_is_kept() {
        FilterRuleDocument saved = service.create(request().name("no-video").build());

        assertThat(saved.getName()).isEqualTo("no-video");
    }

    @Test
    void a_duplicate_name_is_a_conflict() {
        FilterRuleDocument existing = FilterRuleDocument.builder().name("no-video").build();
        when(repository.findByName("no-video")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(request().name("no-video").build()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void the_default_match_type_is_exact_and_rules_start_enabled() {
        FilterRuleDocument saved = service.create(request().build());

        assertThat(saved.getMatchType()).isEqualTo(FilterMatchType.EXACT);
        assertThat(saved.isEnabled()).isTrue();
    }
}
