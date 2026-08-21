package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.settings.MuninSettings;
import de.mhus.hrafnagud.munin.settings.WorkerSwitch;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class SourceListRefreshTick {

    private final SourceListService listService;
    private final MuninSettings.SourceList config;
    private final WorkerSwitch enabled;

    /** The tick cadence, which is read once by the scheduler and so stays a property. */
    private final MuninProperties.SourceList cadence;
    private final AtomicInteger running = new AtomicInteger();

    public SourceListRefreshTick(SourceListService listService, MuninProperties properties,
            MuninSettings settings) {
        this.listService = listService;
        this.config = settings.getSourceList();
        this.cadence = properties.getSourceList();
        this.enabled = new WorkerSwitch("Source-list refresh", config.enabled());
    }

    @Scheduled(fixedDelayString = "${munin.source-list.tickInterval:PT5M}",
            initialDelayString = "${munin.source-list.initialDelay:PT20S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
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
        // Drain the backlog, do not trickle it. A catalogue delivers dozens of
        // lists in one go, all due at once; taking a fixed handful per
        // five-minute tick would spread a fresh instance's first import over
        // an hour, during which it looks broken rather than busy.
        //
        // Bounded twice all the same: batchSize per lease, and maxPerRound
        // over the whole round — one list refresh can create a thousand
        // sources, and a round that never ends is a round that never releases
        // its leases.
        int refreshed = 0;
        while (refreshed < config.maxPerRound().value()) {
            // Deliberately the round's own clock, not a fresh one per batch: a
            // list that becomes due while the round is running belongs to the
            // next round, and re-reading the clock here would let a long round
            // keep finding new work and never finish.
            List<SourceListDocument> claimed = listService.claimDue(now,
                    Math.min(config.batchSize().value(),
                            config.maxPerRound().value() - refreshed));
            if (claimed.isEmpty()) {
                break;
            }
            for (SourceListDocument list : claimed) {
                try {
                    listService.refresh(list, now);
                } catch (RuntimeException e) {
                    log.warn("Refresh of source list {} threw: {}",
                            list.getName(), e.toString());
                }
            }
            refreshed += claimed.size();
        }
        if (refreshed >= config.maxPerRound().value()) {
            // Said out loud, because the alternative is an operator watching a
            // number climb and not knowing whether it has stopped.
            log.info("Source-list round hit the {}-list cap; more are due and the next "
                    + "round continues", config.maxPerRound().value());
        }
        return refreshed;
    }

    /** Configured refresh cadence — surfaced for diagnostics. */
    public long tickIntervalSeconds() {
        return cadence.getTickInterval().getSeconds();
    }
}
