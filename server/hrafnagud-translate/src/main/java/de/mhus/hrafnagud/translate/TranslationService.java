package de.mhus.hrafnagud.translate;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.article.ArticleTranslation;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Translates one article into one language and stores the result.
 *
 * <p>Per article <em>and</em> per language: an article owing two
 * languages is two units of work, so a provider failing on the second
 * does not cost the first. That is also why the storage write is
 * per-language rather than a whole-map replace.
 */
@Service
@Slf4j
public class TranslationService {

    private final ArticleService articleService;
    private final MuninProperties.Translation config;
    private final @Nullable TranslationProvider provider;

    public TranslationService(ArticleService articleService, MuninProperties properties,
            ObjectProvider<TranslationProvider> provider) {
        this.articleService = articleService;
        this.config = properties.getTranslation();
        // ObjectProvider rather than a @Nullable parameter: the provider
        // bean may be absent entirely, or present-but-null when Ode is on
        // the classpath unconfigured. Both mean the same thing here, and
        // this is the one form that treats them the same.
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
     * Translates the first language the article still owes.
     *
     * <p>One language per call rather than draining the backlog: it keeps
     * the unit of work — and therefore the retry, the lease and the
     * failure — the same size as the thing that can go wrong.
     *
     * @return the language translated, or {@code null} when there was
     *         nothing to do
     */
    public @Nullable String translateNext(ArticleDocument article, Instant now) {
        if (provider == null) {
            return null;
        }
        List<String> pending = article.getPendingTranslations();
        if (pending.isEmpty()) {
            return null;
        }
        String language = pending.getFirst();
        String articleId = StringUtils.defaultString(article.getId());

        String title = TextCleaner.truncate(article.getTitle(), config.getMaxSourceChars());
        if (StringUtils.isBlank(title)) {
            // Nothing to translate and nothing that will change on a
            // retry — drop the language rather than burn the budget.
            articleService.recordTranslationFailure(articleId, language,
                    "article has no title to translate", config.getMaxAttempts(), now);
            return null;
        }

        try {
            String translatedTitle = provider.translate(title, language);
            String translatedSummary = translateSummary(article, language);

            articleService.recordTranslation(articleId, language, ArticleTranslation.builder()
                    .title(translatedTitle)
                    .summary(translatedSummary)
                    .engine(provider.name())
                    .translatedAt(now)
                    .build(), now);

            log.debug("Translated article {} into '{}' via {}", articleId, language,
                    provider.name());
            return language;
        } catch (TranslationException e) {
            // A permanent failure is charged the whole budget at once:
            // retrying a rejected token five times only produces five
            // rejections, and the queue should say so and move on.
            int attempts = e.isRetryable()
                    ? article.getTranslationAttempts()
                    : config.getMaxAttempts();
            articleService.recordTranslationFailure(articleId, language, e.getMessage(),
                    attempts, now);
            log.info("Translation of article {} into '{}' failed ({}): {}", articleId, language,
                    e.isRetryable() ? "will retry" : "giving up", e.getMessage());
            return null;
        }
    }

    /**
     * The teaser, when configured and present.
     *
     * <p>A failure here is not allowed to sink the title: a translated
     * headline with an untranslated teaser is a usable archive entry,
     * whereas losing both to one bad call is not.
     */
    private @Nullable String translateSummary(ArticleDocument article, String language) {
        if (!config.isTranslateSummary() || StringUtils.isBlank(article.getSummary())) {
            return null;
        }
        String summary = TextCleaner.truncate(article.getSummary(), config.getMaxSourceChars());
        try {
            return provider == null ? null : provider.translate(summary, language);
        } catch (TranslationException e) {
            log.debug("Summary translation of article {} into '{}' failed, keeping the title: {}",
                    article.getId(), language, e.getMessage());
            return null;
        }
    }
}
