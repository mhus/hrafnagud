package de.mhus.hrafnagud.munin.ingest;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.settings.WorkerSwitch;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.source.SourceService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives feed collection: claim the sources that are due, poll them
 * concurrently, repeat.
 *
 * <p>Sources are claimed in bounded batches rather than queried as a whole.
 * A registry of ten thousand feeds produces a due-list that would not fit
 * comfortably in memory, and more importantly a claim is a lease — holding
 * ten thousand of them while working through the list would block every
 * other worker from touching any of them.
 *
 * <p>Concurrency here is about latency, not throughput. Feed polls are
 * almost entirely waiting on a remote server, and the per-host rate limiter
 * inside {@link HttpFetcher} already caps how fast any single publisher is
 * approached — so the pool size decides how many <em>different</em> hosts
 * are in flight at once, not how hard any of them is hit. Virtual threads
 * make that cheap.
 */
@Component
@Slf4j
public class FeedIngestTick {

    private final SourceService sourceService;
    private final FeedIngestService ingestService;
    private final HttpFetcher fetcher;
    private final Settings.Feed config;
    private final WorkerSwitch enabled;
    private final ExecutorService executor;

    /**
     * Stops a slow round from overlapping the next one. Spring's scheduler
     * is single-threaded per method, but a round that outlives its own
     * interval would otherwise queue up ticks behind it.
     */
    private final AtomicInteger running = new AtomicInteger();

    public FeedIngestTick(SourceService sourceService, FeedIngestService ingestService,
            HttpFetcher fetcher, Settings settings) {
        this.sourceService = sourceService;
        this.ingestService = ingestService;
        this.fetcher = fetcher;
        this.config = settings.getFeed();
        this.enabled = new WorkerSwitch("Feed ingest", config.enabled());
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("munin-feed-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${munin.feed.tickInterval:PT30S}",
            initialDelayString = "${munin.feed.initialDelay:PT10S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
        if (running.get() > 0) {
            log.trace("Feed tick still running — skipping this round");
            return;
        }
        running.incrementAndGet();
        try {
            runRound(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Feed ingest round failed: {}", e.toString());
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * One round: claim and poll a batch.
     *
     * @return number of sources polled
     */
    int runRound(Instant now) {
        List<SourceDocument> claimed = sourceService.claimDue(now, config.batchSize().value());
        if (claimed.isEmpty()) {
            fetcher.evictStaleHosts();
            return 0;
        }
        log.debug("Feed tick: polling {} sources", claimed.size());

        List<? extends Future<?>> futures = claimed.stream()
                .map(source -> executor.submit(() -> pollSafely(source, now)))
                .toList();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return claimed.size();
            } catch (ExecutionException e) {
                log.warn("Feed poll task failed: {}", e.getCause() == null
                        ? e.toString() : e.getCause().toString());
            }
        }
        return claimed.size();
    }

    /**
     * A poll that throws must not take the round with it, and — more
     * importantly — must not leave the source leased until the claim
     * expires. Recording the failure reschedules it on the normal backoff.
     */
    private void pollSafely(SourceDocument source, Instant now) {
        try {
            ingestService.poll(source, now);
        } catch (RuntimeException e) {
            log.warn("Poll of {} threw: {}", source.getName(), e.toString());
            sourceService.recordFetchResult(source.getName(), FetchOutcome.FETCH_ERROR, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null, null,
                    source.getFetchIntervalSeconds(), now);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
