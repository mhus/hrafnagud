package de.mhus.hrafnagud.classify;

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

    /** The Vancetope-backed resolver, or null when Ode is not configured. */
    @Bean
    public @Nullable CategoryResolver odeCategoryResolver(
            ObjectProvider<UrsaEventClient> events,
            @Value("${hrafnagud.classify.event:classify-category}") String eventName) {

        UrsaEventClient client = events.getIfAvailable();
        if (client == null) {
            log.debug("No UrsaEventClient — Vancetope category resolver not wired");
            return null;
        }
        log.info("Category resolver wired to event '{}'", eventName);
        return new OdeCategoryResolver(client, eventName);
    }
}
