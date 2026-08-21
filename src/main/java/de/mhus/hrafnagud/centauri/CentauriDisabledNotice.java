package de.mhus.hrafnagud.centauri;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Says out loud that the feed source is switched off.
 *
 * <p>Without this the off state is silent: {@code @ConditionalOnProperty}
 * skips {@link CentauriConfiguration} entirely, no {@code FeedSource} bean is
 * published, the Ode module's controller is therefore never registered, and
 * every path under {@code /ode/feed} answers 404. What an operator sees is a
 * healthy service, a 404, and nothing in the log — indistinguishable from a
 * broken deployment, and diagnosable only by reading the process environment.
 *
 * <p>That cost two rounds of debugging before the default moved to on, which
 * is why the line stays now that the state is the unusual one.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        prefix = "munin.centauri", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class CentauriDisabledNotice {

    @PostConstruct
    void announce() {
        log.info("Feed source disabled — /ode/feed answers 404. "
                + "Set munin.centauri.enabled=true (HRAFNAGUD_CENTAURI_ENABLED) to serve it.");
    }
}
