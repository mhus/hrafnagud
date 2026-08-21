package de.mhus.hrafnagud.hugin.translate;

import de.mhus.hrafnagud.hugin.BrainAddress;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires a translation provider when one can be built.
 *
 * <p>Uses {@link ObjectProvider} rather than {@code @ConditionalOnBean}
 * on the provider itself. That annotation is only dependable inside an
 * auto-configuration, where Spring guarantees user beans have been
 * registered first; on a component-scanned class the evaluation order is
 * not defined, so the provider could be skipped even though Ode is
 * configured — and the symptom would be a backlog that silently never
 * drains. Asking at bean-creation time has no such ordering problem.
 */
@Configuration
@Slf4j
public class TranslateConfiguration {

    /**
     * The Vancetope-backed provider, or {@code null} when Ode is not
     * configured.
     *
     * <p>A {@code null} bean is legal here and is what
     * {@link TranslationService} expects — it reports itself unavailable
     * and the queue simply goes unworked, which the startup log says out
     * loud.
     *
     * <p>The address is checked as well as the client, because the client
     * exists even when the address is empty — see {@link BrainAddress}. Without
     * that check the startup report claims a provider on an installation that
     * has no brain, which is the one thing that report exists to prevent.
     */
    @Bean
    public @Nullable TranslationProvider odeTranslationProvider(
            ObjectProvider<UrsaEventClient> events,
            @Value("${vance.ode.base-url:}") String baseUrl,
            @Value("${hrafnagud.translate.event:translate-article}") String eventName) {

        if (!BrainAddress.isConfigured(baseUrl)) {
            log.debug("No brain address (vance.ode.base-url is empty) — "
                    + "Vancetope translation provider not wired");
            return null;
        }
        UrsaEventClient client = events.getIfAvailable();
        if (client == null) {
            log.debug("No UrsaEventClient — Vancetope translation provider not wired");
            return null;
        }
        return new OdeTranslationProvider(client, eventName);
    }
}
