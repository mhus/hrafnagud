package de.mhus.hrafnagud.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.ode.core.VanceOdeException;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The seam between Hrafnagud's queue and Ode: what gets sent, and how
 * Ode's failure kinds become a retry decision.
 */
class OdeTranslationProviderTest {

    private UrsaEventClient events;
    private OdeTranslationProvider provider;

    @BeforeEach
    void setUp() {
        events = mock(UrsaEventClient.class);
        provider = new OdeTranslationProvider(events, "translate-article");
    }

    @Test
    void sends_the_text_and_target_language_as_the_event_payload() {
        when(events.requireText(any(), any())).thenReturn("Der Rat hat zugestimmt.");

        String result = provider.translate("The council agreed.", "de");

        assertThat(result).isEqualTo("Der Rat hat zugestimmt.");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).requireText(eq("translate-article"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("text", "The council agreed.")
                .containsEntry("targetLang", "de");
    }

    @Test
    void the_event_name_is_configurable() {
        OdeTranslationProvider custom = new OdeTranslationProvider(events, "other-event");
        when(events.requireText(any(), any())).thenReturn("x");

        custom.translate("t", "de");

        verify(events).requireText(eq("other-event"), any());
    }

    @Test
    void odes_retry_verdict_is_carried_through_rather_than_re_derived() {
        // Ode already decided whether the far end might behave differently
        // next time; forming a second opinion here would let the two drift.
        when(events.requireText(any(), any())).thenThrow(new VanceOdeException(
                VanceOdeException.Kind.TRANSPORT, 0, "brain unreachable"));

        assertThatThrownBy(() -> provider.translate("t", "de"))
                .isInstanceOf(TranslationException.class)
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isTrue());
    }

    @Test
    void an_unauthorized_event_is_not_retried() {
        when(events.requireText(any(), any())).thenThrow(new VanceOdeException(
                VanceOdeException.Kind.UNAUTHORIZED, 401, "bad token"));

        assertThatThrownBy(() -> provider.translate("t", "de"))
                .isInstanceOf(TranslationException.class)
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isFalse())
                .hasMessageContaining("bad token");
    }

    @Test
    void a_blank_result_is_a_permanent_failure_not_a_translation() {
        // Storing it would put an empty title in the archive and call it
        // translated — worse than having no translation at all.
        when(events.requireText(any(), any())).thenReturn("   ");

        assertThatThrownBy(() -> provider.translate("The council agreed.", "de"))
                .isInstanceOf(TranslationException.class)
                .satisfies(e -> assertThat(((TranslationException) e).isRetryable()).isFalse())
                .hasMessageContaining("blank");
    }

    @Test
    void the_provider_names_itself_for_the_stored_engine_field() {
        assertThat(provider.name()).isEqualTo("vance-ode");
    }
}
