package de.mhus.hrafnagud.munin.image;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Works through the image-copy queue.
 *
 * <p>{@code munin.image.enabled} is checked at the start of each round, so
 * copying can be switched on and off while the service runs — and switched
 * off is the state it ships in. The queue only fills while it is on (see
 * {@link ImageService#enqueue}), which means switching it off stops the
 * traffic and switching it on later starts from the articles collected from
 * then on.
 */
@Component
@Slf4j
public class ImageFetchTick {

    private final ImageService imageService;
    private final ImageFetchService fetchService;
    private final Settings.Image config;
    private final WorkerSwitch enabled;
    private final ExecutorService executor;
    private final AtomicInteger running = new AtomicInteger();

    public ImageFetchTick(ImageService imageService, ImageFetchService fetchService,
            Settings settings) {
        this.imageService = imageService;
        this.fetchService = fetchService;
        this.config = settings.getImage();
        this.enabled = new WorkerSwitch("Image copying", config.enabled());
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("munin-image-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${munin.image.tickInterval:PT20S}",
            initialDelayString = "${munin.image.initialDelay:PT45S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
        if (running.get() > 0) {
            log.trace("Image tick still running — skipping this round");
            return;
        }
        running.incrementAndGet();
        try {
            runRound(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Image fetch round failed: {}", e.toString());
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * One round.
     *
     * @return number of images attempted
     */
    int runRound(Instant now) {
        List<ImageDocument> claimed = imageService.claimDue(now, config.batchSize().value());
        if (claimed.isEmpty()) {
            return 0;
        }
        log.debug("Image tick: fetching {} images", claimed.size());

        List<? extends Future<?>> futures = claimed.stream()
                .map(image -> executor.submit(() -> fetchSafely(image, now)))
                .toList();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return claimed.size();
            } catch (ExecutionException e) {
                log.warn("Image fetch task failed: {}", e.getCause() == null
                        ? e.toString() : e.getCause().toString());
            }
        }
        return claimed.size();
    }

    /**
     * A fetch that throws must still record an outcome, or the image stays
     * leased until the claim expires and then repeats the same crash.
     */
    private void fetchSafely(ImageDocument image, Instant now) {
        try {
            fetchService.fetch(image, now);
        } catch (RuntimeException e) {
            log.warn("Image fetch of {} threw: {}", image.getUrl(), e.toString());
            imageService.recordFailure(image.getId(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    image.getAttempts(), now);
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
