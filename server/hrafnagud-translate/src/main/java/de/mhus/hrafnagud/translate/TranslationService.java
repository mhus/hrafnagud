package de.mhus.hrafnagud.translate;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Translates one article into the pivot language and records the result.
 *
 * <p>The translation is written as an {@link EnrichmentDocument}, not
 * onto the article. Running this again with a better model adds a second
 * result rather than destroying the first, which is the only way to find
 * out whether the newer model is actually better.
 */
@Service
@Slf4j
public class TranslationService {

    private final ArticleService articleService;
    private final EnrichmentService enrichmentService;
    private final MuninProperties.Translation config;
    private final @Nullable TranslationProvider provider;

    public TranslationService(ArticleService articleService, EnrichmentService enrichmentService,
            MuninProperties properties, ObjectProvider<TranslationProvider> provider) {
        this.articleService = articleService;
        this.enrichmentService = enrichmentService;
        this.config = properties.getTranslation();
        // ObjectProvider rather than a @Nullable parameter: the provider
        // bean may be absent entirely, or present-but-null when Ode is on
        // the classpath unconfigured. Both mean the same thing here.
        this.provider = provider.getIfAvailable();
    }

    /** {@code true} when something is wired that could do the work. */
    public boolean isAvailable() {
        return provider != null;
    }

    /** Name of the wired provider, or {@code null} when there is none. */
    public @Nullable String providerName() {
        return provider == null ? null : provider.name();
    }

    /**
     * Translates one article.
     *
     * @return {@code true} when a translation was stored
     */
    public boolean translate(ArticleDocument article, Instant now) {
        if (provider == null) {
            return false;
        }
        String pivot = TextCleaner.normalizeLanguage(config.getPivotLanguage());
        if (pivot == null) {
            return false;
        }
        String articleId = StringUtils.defaultString(article.getId());

        String title = TextCleaner.truncate(article.getTitle(), config.getMaxSourceChars());
        if (StringUtils.isBlank(title)) {
            // Nothing to translate and nothing a retry would change.
            articleService.recordTranslationFailure(articleId,
                    "article has no title to translate", config.getMaxAttempts(), now);
            return false;
        }
        String summary = config.isTranslateSummary() && StringUtils.isNotBlank(article.getSummary())
                ? TextCleaner.truncate(article.getSummary(), config.getMaxSourceChars())
                : null;

        try {
            TranslatedText translated = provider.translate(title, summary, pivot);

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("title", translated.getTitle());
            if (translated.getSummary() != null) {
                content.put("summary", translated.getSummary());
            }

            enrichmentService.record(EnrichmentDocument.builder()
                    .articleId(articleId)
                    .type(EnrichmentType.TRANSLATION)
                    .producer(provider.name())
                    .model(provider.model())
                    .language(pivot)
                    .createdAt(now)
                    .content(content)
                    .build());
            articleService.recordTranslated(articleId);

            log.debug("Translated article {} into '{}' via {}", articleId, pivot, provider.name());
            return true;
        } catch (TranslationException e) {
            // A permanent failure is charged the whole budget at once:
            // retrying a rejected token five times only produces five
            // rejections, and the queue should say so and move on.
            int attempts = e.isRetryable()
                    ? article.getTranslationAttempts()
                    : config.getMaxAttempts();
            articleService.recordTranslationFailure(articleId, e.getMessage(), attempts, now);
            log.info("Translation of article {} failed ({}): {}", articleId,
                    e.isRetryable() ? "will retry" : "giving up", e.getMessage());
            return false;
        }
    }
}
