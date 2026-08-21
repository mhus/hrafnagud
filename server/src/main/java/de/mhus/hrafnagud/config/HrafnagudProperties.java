package de.mhus.hrafnagud.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What belongs to the service rather than to one of its halves.
 *
 * <p>Three roots, one per layer: {@code munin.*} collects and stores,
 * {@code hugin.*} thinks, and {@code hrafnagud.*} is the machinery both of them
 * stand on. A value lands here when naming a layer for it would be a claim that
 * is not true — the settings layer serves both, so it is neither Munin's nor
 * Hugin's.
 */
@ConfigurationProperties(prefix = "hrafnagud")
@Data
public class HrafnagudProperties {

    private final Settings settings = new Settings();

    /**
     * The settings layer itself, which can only be configured from here — a
     * setting that decided how settings are read would be a loop.
     */
    @Data
    public static class Settings {

        /**
         * How often stored overrides are re-read.
         *
         * <p>Everything written through the API is visible at once, so this
         * only matters for a value changed straight in the database and for a
         * second instance the deployment is not supposed to have. The
         * collection holds one small document per changed value, which is why
         * polling it is cheaper than being clever about it.
         */
        private Duration refreshInterval = Duration.ofSeconds(30);
    }
}
