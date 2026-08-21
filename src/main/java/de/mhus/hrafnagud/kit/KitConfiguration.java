package de.mhus.hrafnagud.kit;

import de.mhus.vance.ode.kit.KitSource;
import de.mhus.vance.ode.kit.StaticKitSource;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the two {@link KitSource} beans, which is what makes the Ode module
 * serve them.
 *
 * <p>Off by default, for the same reason the file mount is: this endpoint hands
 * out configuration that includes API keys, so an unguarded path gives away
 * read access to the whole archive. Set {@code vance.ode.kit.apiKey} and the
 * key travels only to a caller that already holds a secret.
 *
 * <p>What the two kits are for, and why they live in this repository rather
 * than in a kit collection: {@code specs/kits.md}.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "munin.kit", name = "enabled", havingValue = "true")
public class KitConfiguration {

    /**
     * The brain side of translation — served as it lies, because nothing in it
     * is per-installation.
     *
     * <p>Its own token is the exception, and it stays inline: the token is a
     * field inside an event document, and rewriting a nested YAML value from
     * here would be string surgery on a document somebody else authored. The
     * kit says instead how to move it into a setting, which is the supported
     * way and leaves the document alone.
     */
    @Bean
    public KitSource translationKitSource() {
        return StaticKitSource.fromClasspath("hrafnagud-translation", "kits/translation", "1.0",
                "The brain side of hrafnagud's translation: event, script and recipe");
    }

    /** The reader side: endpoints, the mount, and a skill that says what is in it. */
    @Bean
    public KitSource archiveKitSource(
            @Value("${vance.ode.centauri.apiKey:}") String centauriKey,
            @Value("${vance.ode.zarniwoop.apiKey:}") String zarniwoopKey,
            @Value("${vance.ode.jaglan.apiKey:}") String jaglanKey,
            @Value("${vance.ode.kit.apiKey:}") String kitKey,
            @Value("${vance.ode.kit.path:/kit}") String path) {

        if (kitKey.isBlank()) {
            // WARN rather than INFO because of what this particular endpoint
            // serves: the other surfaces hand out the archive, this one hands
            // out the keys to it.
            log.warn("Kit endpoint enabled at '{}' with NO api key — anyone who reaches the "
                    + "path receives this service's endpoint keys. Set vance.ode.kit.apiKey, "
                    + "or munin.kit.enabled=false.", path);
        } else {
            log.info("Kit endpoint enabled at '{}' (api key required)", path);
        }

        return new ArchiveKitSource("hrafnagud-archive", "kits/archive", "1.0",
                Map.of("centauri", centauriKey,
                        "zarniwoop", zarniwoopKey,
                        "jaglan", jaglanKey));
    }
}
