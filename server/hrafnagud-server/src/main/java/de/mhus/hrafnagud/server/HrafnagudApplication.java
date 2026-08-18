package de.mhus.hrafnagud.server;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Hrafnagud — a news collector.
 *
 * <p>Scans {@code de.mhus.hrafnagud} rather than only this package, so the
 * feature modules are picked up without each having to ship its own
 * auto-configuration.
 *
 * <p>{@link EnableMongoRepositories} has to name the same root explicitly.
 * Repository scanning does <em>not</em> follow {@code scanBasePackages} —
 * it starts from the package of this class — so without it the repository
 * interfaces in the feature modules are simply not found, and the failure
 * surfaces as a missing bean at startup rather than as a scanning warning.
 */
@SpringBootApplication(scanBasePackages = "de.mhus.hrafnagud")
@EnableMongoRepositories(basePackages = "de.mhus.hrafnagud")
@EnableConfigurationProperties(MuninProperties.class)
@EnableScheduling
public class HrafnagudApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrafnagudApplication.class, args);
    }
}
