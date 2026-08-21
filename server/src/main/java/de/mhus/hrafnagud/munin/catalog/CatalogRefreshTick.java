package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.settings.WorkerSwitch;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reads catalogues that are due.
 *
 * <p>The top of the chain that makes the archive keep itself up to date
 * without anybody pressing anything: this tick discovers lists, the
 * source-list tick turns those into feeds, the feed tick polls them. Each
 * runs on its own schedule, so a new catalogue becomes articles within a few
 * ticks and nothing needs an operator in the loop.
 *
 * <p>Sequential and one catalogue per round, like the list tick and for the
 * same reason: a refresh writes across the whole list registry, and two
 * reconciliation passes in flight over overlapping sets would buy nothing.
 */
@Component
@Slf4j
public class CatalogRefreshTick {

    private final SourceCatalogService catalogService;
    private final Settings.Catalog config;
    private final WorkerSwitch enabled;

    /** The tick cadence, which is read once by the scheduler and so stays a property. */
    private final MuninProperties.Catalog cadence;
    private final AtomicInteger running = new AtomicInteger();

    public CatalogRefreshTick(SourceCatalogService catalogService, MuninProperties properties,
            Settings settings) {
        this.catalogService = catalogService;
        this.config = settings.getCatalog();
        this.cadence = properties.getCatalog();
        this.enabled = new WorkerSwitch("Catalogue refresh", config.enabled());
    }

    @Scheduled(fixedDelayString = "${munin.catalog.tickInterval:PT15M}",
            initialDelayString = "${munin.catalog.initialDelay:PT30S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
        if (running.get() > 0) {
            log.trace("Catalog tick still running — skipping this round");
            return;
        }
        running.incrementAndGet();
        try {
            runRound(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Catalog refresh round failed: {}", e.toString());
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * One round.
     *
     * @return number of catalogues refreshed
     */
    int runRound(Instant now) {
        List<SourceCatalogDocument> claimed = catalogService.claimDue(now, 1);
        for (SourceCatalogDocument catalog : claimed) {
            try {
                catalogService.refresh(catalog, now);
            } catch (RuntimeException e) {
                log.warn("Refresh of catalog {} threw: {}", catalog.getName(), e.toString());
            }
        }
        return claimed.size();
    }

    /** Configured refresh cadence — surfaced for diagnostics. */
    public long tickIntervalSeconds() {
        return cadence.getTickInterval().getSeconds();
    }
}
