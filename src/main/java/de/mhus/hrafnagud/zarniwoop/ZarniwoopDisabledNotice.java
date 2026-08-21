package de.mhus.hrafnagud.zarniwoop;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * The research surface's counterpart to {@code CentauriDisabledNotice} — see
 * there for why an off switch that says nothing is worse than the switch
 * itself.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        prefix = "munin.zarniwoop", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class ZarniwoopDisabledNotice {

    @PostConstruct
    void announce() {
        log.info("Research source disabled — /ode/search answers 404. "
                + "Set munin.zarniwoop.enabled=true (HRAFNAGUD_ZARNIWOOP_ENABLED) to serve it.");
    }
}
