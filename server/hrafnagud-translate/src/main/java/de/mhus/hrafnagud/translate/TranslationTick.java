package de.mhus.hrafnagud.translate;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Works the translation backlog.
 *
 * <p>Sequential, unlike the feed and content ticks. Those wait on many
 * different hosts and gain from overlapping; this one calls a single
 * service, so concurrency here would only mean hitting that service
 * harder — and behind it sits a model with its own rate limits, which is
 * not a place to push.
 */
@Component
@Slf4j
public class TranslationTick {

    private final ArticleService articleService;
    private final TranslationService translationService;
    private final MuninProperties.Translation config;
    private final AtomicInteger running = new AtomicInteger();

    public TranslationTick(ArticleService articleService, TranslationService translationService,
            MuninProperties properties) {
        this.articleService = articleService;
        this.translationService = translationService;
        this.config = properties.getTranslation();
    }

    /**
     * Says at startup what the configuration actually amounts to.
     *
     * <p>Targets configured with no provider wired is the failure that
     * would otherwise be silent: articles queue up, nothing drains them,
     * and the archive looks like it is translating.
     */
    @PostConstruct
    void reportConfiguration() {
        if (config.getTargets().isEmpty()) {
            log.info("Translation is off — no munin.translation.targets configured");
        } else if (!translationService.isAvailable()) {
            log.warn("Translation targets {} are configured but no provider is wired — "
                            + "articles will queue and nothing will translate them. "
                            + "Set vance.ode.base-url to use a Vancetope brain.",
                    config.getTargets());
        } else {
            log.info("Translating into {} via {}", config.getTargets(),
                    translationService.providerName());
        }
    }

    @Scheduled(fixedDelayString = "${munin.translation.tickInterval:PT20S}",
            initialDelayString = "${munin.translation.initialDelay:PT30S}")
    public void tick() {
        if (config.getTargets().isEmpty() || !translationService.isAvailable()) {
            return;
        }
        if (running.get() > 0) {
            log.trace("Translation tick still running — skipping this round");
            return;
        }
        running.incrementAndGet();
        try {
            runRound(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Translation round failed: {}", e.toString());
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
                articleService.claimTranslationDue(now, config.getBatchSize());
        if (claimed.isEmpty()) {
            return 0;
        }
        log.debug("Translation tick: {} articles claimed", claimed.size());

        for (ArticleDocument article : claimed) {
            try {
                translationService.translateNext(article, now);
            } catch (RuntimeException e) {
                // translateNext already records TranslationException; this
                // catches everything else so one bad article cannot end the
                // round and strand the rest of the batch under its lease.
                log.warn("Translating article {} threw: {}", article.getId(), e.toString());
                articleService.recordTranslationFailure(
                        String.valueOf(article.getId()),
                        article.getPendingTranslations().isEmpty()
                                ? "?" : article.getPendingTranslations().getFirst(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        article.getTranslationAttempts(), now);
            }
        }
        return claimed.size();
    }
}
