package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reads source lists that are due.
 *
 * <p>Sequential, unlike the feed tick. Refreshes are rare (a directory is
 * re-read daily, not every few minutes) and each one writes across the
 * whole source registry, so running two at once would buy nothing and would
 * put two reconciliation passes over overlapping sets of sources in flight
 * at the same time.
 */
@Component
@ConditionalOnProperty(name = "munin.source-list.enabled", havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class SourceListRefreshTick {

    private final SourceListService listService;
    private final MuninProperties.SourceList config;
    private final AtomicInteger running = new AtomicInteger();

    public SourceListRefreshTick(SourceListService listService, MuninProperties properties) {
        this.listService = listService;
        this.config = properties.getSourceList();
    }

    @Scheduled(fixedDelayString = "${munin.source-list.tickInterval:PT5M}",
            initialDelayString = "${munin.source-list.initialDelay:PT20S}")
    public void tick() {
        if (running.get() > 0) {
            log.trace("Source-list tick still running — skipping this round");
            return;
        }
        running.incrementAndGet();
        try {
            runRound(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Source-list refresh round failed: {}", e.toString());
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * One round.
     *
     * @return number of lists refreshed
     */
    int runRound(Instant now) {
        // One list per round: a refresh can create a thousand sources, and
        // doing several back to back would make one tick arbitrarily long
        // for no benefit — the next tick is minutes away either way.
        List<SourceListDocument> claimed = listService.claimDue(now, 1);
        for (SourceListDocument list : claimed) {
            try {
                listService.refresh(list, now);
            } catch (RuntimeException e) {
                log.warn("Refresh of source list {} threw: {}", list.getName(), e.toString());
            }
        }
        return claimed.size();
    }

    /** Configured refresh cadence — surfaced for diagnostics. */
    public long tickIntervalSeconds() {
        return config.getTickInterval().getSeconds();
    }
}
