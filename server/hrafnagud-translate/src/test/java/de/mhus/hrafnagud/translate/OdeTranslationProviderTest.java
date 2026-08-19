package de.mhus.hrafnagud.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.ode.core.VanceOdeException;
import de.mhus.vance.ode.ursa.EventResult;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The seam between Hrafnagud's queue and Ode: what gets sent, what is
 * accepted back, and how Ode's failure kinds become a retry decision.
 */
class OdeTranslationProviderTest {

    private UrsaEventClient events;
    private OdeTranslationProvider provider;

    @BeforeEach
    void setUp() {
        events = mock(UrsaEventClient.class);
        provider = new OdeTranslationProvider(events, "translate-article");
    }

    private static EventResult resultWith(Map<String, Object> output) {
        return EventResult.builder()
                .event("translate-article")
                .target("script:_vance/scripts/translate-article.js")
                .output(output)
                .build();
    }

    @Test
    void sends_title_teaser_and_target_language_in_one_event() {
        when(events.fire(any(), any())).thenReturn(resultWith(Map.of(
                "title", "Der Rat hat zugestimmt.",
                "summary", "Die Abstimmung beendete eine Debatte.")));

        TranslatedText translated =
                provider.translate("The council agreed.", "The vote ended a debate.", "de");

        assertThat(translated.getTitle()).isEqualTo("Der Rat hat zugestimmt.");
        assertThat(translated.getSummary()).isEqualTo("Die Abstimmung beendete eine Debatte.");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).fire(eq("translate-article"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("title", "The council agreed.")
                .containsEntry("summary", "The vote ended a debate.")
                .containsEntry("targetLang", "de");
    }

    @Test
    void a_missing_teaser_is_sent_as_empty_rather_than_omitted() {
        // The brain-side schema expects the field; leaving it out would
        // make the recipe's contract conditional for no reason.
        when(events.fire(any(), any())).thenReturn(resultWith(Map.of("title", "Titel")));

        provider.translate("Title", null, "de");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).fire(any(), payload.capture());
        assertThat(payload.getValue()).containsEntry("summary", "");
    }

    @Test
    void a_blank_returned_summary_becomes_null_not_an_empty_string() {
        when(events.fire(any(), any()))
                .thenReturn(resultWith(Map.of("title", "Titel", "summary", "   ")));

        assertThat(provider.translate("Title", "Teaser", "de").getSummary()).isNull();
    }

    @Test
    void an_event_with_no_output_is_a_permanent_failure_that_names_the_likely_cause() {
        when(events.fire(any(), any())).thenReturn(EventResult.builder()
                .event("translate-article").target("t").runId("run-1").build());

        assertThatThrownBy(() -> provider.translate("Title", null, "de"))
                .isInstanceOf(TranslationException.class)
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isFalse())
                .hasMessageContaining("async");
    }

    @Test
    void a_blank_title_is_a_permanent_failure_not_a_translation() {
        // Storing it would put an empty headline in the archive and call
        // it translated — worse than having no translation at all.
        when(events.fire(any(), any())).thenReturn(resultWith(Map.of("title", "  ")));

        assertThatThrownBy(() -> provider.translate("The council agreed.", null, "de"))
                .isInstanceOf(TranslationException.class)
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isFalse())
                .hasMessageContaining("no title");
    }

    @Test
    void odes_retry_verdict_is_carried_through_rather_than_re_derived() {
        when(events.fire(any(), any())).thenThrow(new VanceOdeException(
                VanceOdeException.Kind.TRANSPORT, 0, "brain unreachable"));

        assertThatThrownBy(() -> provider.translate("t", null, "de"))
                .isInstanceOf(TranslationException.class)
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isTrue());
    }

    @Test
    void an_unauthorized_event_is_not_retried() {
        when(events.fire(any(), any())).thenThrow(new VanceOdeException(
                VanceOdeException.Kind.UNAUTHORIZED, 401, "bad token"));

        assertThatThrownBy(() -> provider.translate("t", null, "de"))
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isFalse())
                .hasMessageContaining("bad token");
    }

    @Test
    void the_event_name_is_configurable() {
        when(events.fire(any(), any())).thenReturn(resultWith(Map.of("title", "x")));

        new OdeTranslationProvider(events, "other-event").translate("t", null, "de");

        verify(events).fire(eq("other-event"), any());
    }

    @Test
    void the_provider_names_itself_for_the_enrichment_record() {
        assertThat(provider.name()).isEqualTo("vance-ode");
    }
}
