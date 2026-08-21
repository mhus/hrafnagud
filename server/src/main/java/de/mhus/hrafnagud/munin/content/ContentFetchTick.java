package de.mhus.hrafnagud.munin.content;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.settings.WorkerSwitch;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Works through the body-fetch queue.
 *
 * <p>{@code munin.content.enabled} is off by default and is checked at the
 * start of each round rather than deciding whether this bean exists. Fetching
 * publisher pages stays opt-in either way; what changes is that turning it on
 * is a setting rather than a restart, and that the answer to "is it crawling?"
 * comes from the log and the settings screen instead of from the bean graph.
 */
@Component
@Slf4j
public class ContentFetchTick {

    private final ArticleService articleService;
    private final ContentFetchService fetchService;
    private final Settings.Content config;
    private final WorkerSwitch enabled;
    private final ExecutorService executor;
    private final AtomicInteger running = new AtomicInteger();

    public ContentFetchTick(ArticleService articleService, ContentFetchService fetchService,
            Settings settings) {
        this.articleService = articleService;
        this.fetchService = fetchService;
        this.config = settings.getContent();
        this.enabled = new WorkerSwitch("Body fetching", config.enabled());
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("munin-content-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${munin.content.tickInterval:PT15S}",
            initialDelayString = "${munin.content.initialDelay:PT30S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
        if (running.get() > 0) {
            log.trace("Content tick still running — skipping this round");
            return;
        }
        running.incrementAndGet();
        try {
            runRound(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Content fetch round failed: {}", e.toString());
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * One round.
     *
     * @return number of articles attempted
     */
    int runRound(Instant now) {
        List<ArticleDocument> claimed =
                articleService.claimContentDue(now, config.batchSize().value());
        if (claimed.isEmpty()) {
            return 0;
        }
        log.debug("Content tick: fetching {} article bodies", claimed.size());

        List<? extends Future<?>> futures = claimed.stream()
                .map(article -> executor.submit(() -> fetchSafely(article, now)))
                .toList();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return claimed.size();
            } catch (ExecutionException e) {
                log.warn("Content fetch task failed: {}", e.getCause() == null
                        ? e.toString() : e.getCause().toString());
            }
        }
        return claimed.size();
    }

    /**
     * A fetch that throws must still record an outcome, or the article stays
     * leased until the claim expires and then repeats the same crash.
     */
    private void fetchSafely(ArticleDocument article, Instant now) {
        try {
            fetchService.fetch(article, now);
        } catch (RuntimeException e) {
            log.warn("Body fetch of {} threw: {}", article.getUrl(), e.toString());
            articleService.recordContentFailure(StringUtils.defaultString(article.getId()),
                    ContentStatus.PENDING, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    article.getContentAttempts(), now);
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
