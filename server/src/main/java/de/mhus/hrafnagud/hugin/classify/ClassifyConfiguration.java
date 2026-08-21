package de.mhus.hrafnagud.hugin.classify;

import de.mhus.hrafnagud.hugin.BrainAddress;
import de.mhus.hrafnagud.munin.category.CategoryResolver;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the category resolver when a brain can be reached.
 *
 * <p>{@code ObjectProvider} rather than {@code @ConditionalOnBean}, for the
 * reason the translation wiring documents: that annotation is only dependable
 * inside an auto-configuration, and on a component-scanned class the
 * evaluation order against user beans is undefined — the symptom would be a
 * backlog that never drains.
 */
@Configuration
@Slf4j
public class ClassifyConfiguration {

    /**
     * The Vancetope-backed resolver, or null when Ode is not configured.
     *
     * <p>The address is checked as well as the client: an empty
     * {@code vance.ode.base-url} still produces a client, and a resolver built
     * on one would turn every mapping into a failed attempt. See
     * {@link BrainAddress}.
     */
    @Bean
    public @Nullable CategoryResolver odeCategoryResolver(
            ObjectProvider<UrsaEventClient> events,
            @Value("${vance.ode.base-url:}") String baseUrl,
            @Value("${hrafnagud.classify.event:classify-category}") String eventName) {

        UrsaEventClient client = BrainAddress.isConfigured(baseUrl)
                ? events.getIfAvailable()
                : null;
        if (client == null) {
            // Said out loud rather than at debug level: stage two being off is
            // indistinguishable, from the outside, from a vocabulary that has
            // nothing left to resolve — and the difference is whether somebody
            // needs to configure something.
            log.info("Category resolution has no brain — mappings that the bundled table "
                    + "cannot settle will stay unresolved. Set vance.ode.base-url to use one.");
            return null;
        }
        log.info("Category resolver wired to event '{}'", eventName);
        return new OdeCategoryResolver(client, eventName);
    }
}
