package de.mhus.hrafnagud.munin.net;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Spaces outbound requests per host across all workers.
 *
 * <p>A source-list import routinely puts dozens of one publisher's feeds
 * into the registry at once, and they then all come due in the same tick.
 * Without pacing, that publisher sees a burst from a single IP and responds
 * the way any operator would — by blocking it. The limiter hands out time
 * slots per host: each caller is told when its turn is and waits for it.
 *
 * <p>Slots are handed out atomically, so N concurrent callers for one host
 * queue up behind each other rather than all sleeping until the same
 * instant and then firing together.
 */
@Slf4j
public class HostRateLimiter {

    /** Host to the most recently granted slot, in epoch millis. */
    private final Map<String, Long> slots = new ConcurrentHashMap<>();

    /**
     * The spacing, read per call rather than captured.
     *
     * <p>A supplier rather than a number because this is the control an
     * operator reaches for while a publisher is complaining, and waiting for a
     * restart to widen it defeats the purpose. A supplier also keeps this class
     * free of the settings machinery, so it stays testable with a lambda.
     */
    private final Supplier<Duration> minHostInterval;

    public HostRateLimiter(Supplier<Duration> minHostInterval) {
        this.minHostInterval = minHostInterval;
    }

    private long intervalMillis() {
        return Math.max(0, minHostInterval.get().toMillis());
    }

    /** Entries untouched for this long are dropped by {@link #evictStale}. */
    private long staleMillis() {
        return Math.max(intervalMillis() * 100, Duration.ofHours(1).toMillis());
    }

    /**
     * Blocks until this caller's slot for {@code host} is due.
     *
     * @throws InterruptedException when the worker is shutting down; the
     *         caller must abandon the request rather than retry
     */
    public void acquire(String host) throws InterruptedException {
        long intervalMillis = intervalMillis();
        if (intervalMillis <= 0 || host.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long slot = slots.compute(host,
                (key, previous) -> previous == null ? now : Math.max(previous + intervalMillis, now));
        long waitMillis = slot - now;
        if (waitMillis > 0) {
            log.trace("HostRateLimiter: waiting {} ms for host {}", waitMillis, host);
            Thread.sleep(waitMillis);
        }
    }

    /**
     * Drops hosts not seen recently. Called from the ingest tick — a
     * worldwide registry touches tens of thousands of hosts, and the map
     * would otherwise only ever grow.
     */
    public int evictStale() {
        long cutoff = System.currentTimeMillis() - staleMillis();
        int removed = 0;
        for (Iterator<Map.Entry<String, Long>> it = slots.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < cutoff) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Number of hosts currently tracked. */
    public int trackedHosts() {
        return slots.size();
    }
}
