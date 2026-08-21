package de.mhus.hrafnagud.munin.category;

import de.mhus.hrafnagud.munin.settings.MuninSettings;
import de.mhus.hrafnagud.munin.settings.WorkerSwitch;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Works the category backlog: what stage one could not settle goes to a model,
 * most-used first.
 *
 * <p>Sequential, like the translation tick and for the same reason — behind it
 * sits one service with its own rate limits, and concurrency here would only
 * mean pushing harder on it.
 *
 * <p>The queue drains and stays drained. Unlike articles, categories are a
 * bounded set that grows only at its tail: once the vocabulary of a source is
 * resolved, this tick finds nothing to do for days.
 */
@Component
@Slf4j
public class CategoryResolutionTick {

    private final CategoryMappingService mappingService;
    private final ObjectProvider<CategoryResolver> resolverProvider;
    private final MuninSettings.Category config;
    private final WorkerSwitch enabled;
    private final AtomicInteger running = new AtomicInteger();

    public CategoryResolutionTick(CategoryMappingService mappingService,
            ObjectProvider<CategoryResolver> resolverProvider, MuninSettings settings) {
        this.mappingService = mappingService;
        this.resolverProvider = resolverProvider;
        this.config = settings.getCategory();
        this.enabled = new WorkerSwitch("Category resolution", config.enabled());
    }

    @Scheduled(fixedDelayString = "${munin.category.tickInterval:PT30S}",
            initialDelayString = "${munin.category.initialDelay:PT40S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
        CategoryResolver resolver = resolverProvider.getIfAvailable();
        if (resolver == null || running.get() > 0) {
            return;
        }
        running.incrementAndGet();
        try {
            runRound(resolver, Instant.now());
        } catch (RuntimeException e) {
            log.warn("Category resolution round failed: {}", e.toString());
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * One round.
     *
     * @return number of mappings decided
     */
    int runRound(CategoryResolver resolver, Instant now) {
        List<CategoryMappingDocument> claimed =
                mappingService.claimDue(now, config.batchSize().value());
        int decided = 0;
        for (CategoryMappingDocument mapping : claimed) {
            try {
                CategoryResolver.Decision decision =
                        resolver.resolve(mapping.getRaw(), mapping.getTopicId());
                mappingService.applyResolution(mapping.getKey(), decision.status(),
                        decision.topicId(), decision.note(), now);
                decided++;
            } catch (RuntimeException e) {
                // A failed call is not a decision: the category keeps its
                // status and is retried, which is why "cannot reach the model"
                // and "this is not a topic" must not share a code path.
                mappingService.fail(mapping.getKey(), e.toString(), now);
                log.debug("Category '{}' could not be resolved: {}",
                        mapping.getRaw(), e.toString());
            }
        }
        if (decided > 0) {
            log.info("Category resolution: {} of {} claimed mappings decided", decided,
                    claimed.size());
        }
        return decided;
    }
}
