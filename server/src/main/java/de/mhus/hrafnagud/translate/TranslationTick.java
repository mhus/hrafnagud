package de.mhus.hrafnagud.translate;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.settings.MuninSettings;
import de.mhus.hrafnagud.munin.settings.WorkerSwitch;
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
 *
 * <p><b>Not registered at all unless {@code munin.translation.enabled} is
 * true.</b> Off by default, like the body fetch: both spend somebody else's
 * resources — publisher bandwidth there, model time and money here — and
 * neither should start because a service was installed. What stays behind
 * when it is off is {@link TranslateConfiguration}, which reports the
 * configuration at startup either way, so "disabled" is a line in the log
 * rather than a silence.
 */
@Component
@Slf4j
public class TranslationTick {

    private final ArticleService articleService;
    private final TranslationService translationService;
    private final MuninSettings.Translation config;
    private final WorkerSwitch enabled;
    private final AtomicInteger running = new AtomicInteger();

    public TranslationTick(ArticleService articleService, TranslationService translationService,
            MuninSettings settings) {
        this.articleService = articleService;
        this.translationService = translationService;
        this.config = settings.getTranslation();
        this.enabled = new WorkerSwitch("Translation", config.enabled());
    }

    @Scheduled(fixedDelayString = "${munin.translation.tickInterval:PT20S}",
            initialDelayString = "${munin.translation.initialDelay:PT30S}")
    public void tick() {
        if (!enabled.isOn()) {
            return;
        }
        if (config.pivotLanguage().value().isBlank() || !translationService.isAvailable()) {
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
                articleService.claimTranslationDue(now, config.batchSize().value());
        if (claimed.isEmpty()) {
            return 0;
        }
        log.debug("Translation tick: {} articles claimed", claimed.size());

        for (ArticleDocument article : claimed) {
            try {
                translationService.translate(article, now);
            } catch (RuntimeException e) {
                // translate() already records TranslationException; this
                // catches everything else so one bad article cannot end the
                // round and strand the rest of the batch under its lease.
                log.warn("Translating article {} threw: {}", article.getId(), e.toString());
                articleService.recordTranslationFailure(String.valueOf(article.getId()),
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        article.getTranslationAttempts(), now);
            }
        }
        return claimed.size();
    }
}
