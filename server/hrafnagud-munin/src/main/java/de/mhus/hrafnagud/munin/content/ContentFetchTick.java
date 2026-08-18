package de.mhus.hrafnagud.munin.content;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.config.MuninProperties;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Works through the body-fetch queue.
 *
 * <p>Only registered when {@code munin.content.enabled} is true. Fetching
 * publisher pages is opt-in, so with the default configuration this bean
 * does not exist rather than existing and doing nothing — which keeps the
 * "is it crawling?" question answerable from the bean graph.
 */
@Component
@ConditionalOnProperty(name = "munin.content.enabled", havingValue = "true")
@Slf4j
public class ContentFetchTick {

    private final ArticleService articleService;
    private final ContentFetchService fetchService;
    private final MuninProperties.Content config;
    private final ExecutorService executor;
    private final AtomicInteger running = new AtomicInteger();

    public ContentFetchTick(ArticleService articleService, ContentFetchService fetchService,
            MuninProperties properties) {
        this.articleService = articleService;
        this.fetchService = fetchService;
        this.config = properties.getContent();
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("munin-content-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${munin.content.tickInterval:PT15S}",
            initialDelayString = "${munin.content.initialDelay:PT30S}")
    public void tick() {
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
                articleService.claimContentDue(now, config.getBatchSize());
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
