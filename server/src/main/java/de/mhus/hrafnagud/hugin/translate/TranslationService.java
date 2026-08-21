package de.mhus.hrafnagud.hugin.translate;

import de.mhus.hrafnagud.api.enrichment.EnrichmentType;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentDocument;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
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
    private final Settings.Translation config;

    /**
     * Everything wired, which is nought, one or two of them.
     *
     * <p>A list rather than one bean, because {@code hugin.translation.provider}
     * chooses between them at runtime — that is what makes putting the same
     * articles through a brain and through a model directly a setting rather
     * than a deployment.
     *
     * <p>Collected through an {@code ObjectProvider} rather than injected as a
     * {@code List}: a required list with no candidates is a startup failure,
     * and "no provider wired" is this service's normal, documented state. Both
     * configurations return null when their credential is missing, so with
     * neither configured this is simply empty.
     */
    private final List<TranslationProvider> providers;

    /** Last provider announced, so a runtime switch appears in the log once. */
    private final AtomicReference<@Nullable String> announced = new AtomicReference<>();

    public TranslationService(ArticleService articleService, EnrichmentService enrichmentService,
            Settings settings, ObjectProvider<TranslationProvider> providers) {
        this.articleService = articleService;
        this.enrichmentService = enrichmentService;
        this.config = settings.getTranslation();
        this.providers = providers.stream().toList();
    }

    /**
     * The provider that should do the work, or {@code null} when there is
     * none to choose.
     *
     * <p>Resolved per call, so a change to {@code hugin.translation.provider}
     * takes effect on the next article. Three cases, and the third is the
     * reason this is not simply "the first one":
     *
     * <ul>
     *   <li>the setting names one — that one, or nothing if the name is
     *       unknown. Falling back to another provider would spend money the
     *       operator did not ask to spend.</li>
     *   <li>the setting is blank and one provider is wired — that one.</li>
     *   <li>the setting is blank and several are wired — <b>nothing</b>, with
     *       a warning. Two ways to pay for a translation and no instruction
     *       which to use is a question, not a default.</li>
     * </ul>
     */
    @Nullable
    TranslationProvider provider() {
        String wanted = config.provider().value().trim();
        TranslationProvider resolved = resolve(wanted);
        String name = resolved == null ? null : resolved.name();
        String previous = announced.getAndSet(name);
        if (!Objects.equals(previous, name) && name != null) {
            log.info("Translating via {} ({} wired: {})", name, providers.size(), names());
        }
        return resolved;
    }

    private @Nullable TranslationProvider resolve(String wanted) {
        if (!wanted.isEmpty()) {
            for (TranslationProvider candidate : providers) {
                if (candidate.name().equalsIgnoreCase(wanted)) {
                    return candidate;
                }
            }
            log.warn("hugin.translation.provider is '{}', which is not wired ({}) — "
                    + "nothing translates until it names one of them", wanted, names());
            return null;
        }
        if (providers.size() == 1) {
            return providers.getFirst();
        }
        if (providers.size() > 1) {
            log.warn("{} translation providers are wired ({}) and "
                    + "hugin.translation.provider names none — set it to one of them",
                    providers.size(), names());
        }
        return null;
    }

    private String names() {
        return providers.isEmpty()
                ? "none"
                : String.join(", ", providers.stream().map(TranslationProvider::name).toList());
    }

    /**
     * Says at startup what the configuration actually amounts to.
     *
     * <p>Here rather than on {@link TranslationTick}, because this bean exists
     * in every one of the four states while the tick only runs in one of them.
     * All three failure modes are otherwise silent — the queue fills, nothing
     * drains it, and the archive looks like it is translating.
     */
    @PostConstruct
    void reportConfiguration() {
        if (config.pivotLanguage().value().isBlank()) {
            log.info("Translation is off — no hugin.translation.pivotLanguage configured");
        } else if (!config.enabled().value()) {
            log.warn("Pivot language '{}' is configured but hugin.translation.enabled is false"
                            + " — articles will queue and nothing will translate them.",
                    config.pivotLanguage().value());
        } else if (!isAvailable()) {
            log.warn("Pivot language '{}' is configured but no provider will run — articles "
                            + "will queue and nothing will translate them. Wired: {}. "
                            + "Set vance.ode.base-url for a Vancetope brain or "
                            + "hugin.gemini.apiKey for a model directly, and name one in "
                            + "hugin.translation.provider when both are.",
                    config.pivotLanguage().value(), names());
        } else {
            log.info("Translating into '{}' via {}",
                    config.pivotLanguage().value(), providerName());
        }
    }

    /** {@code true} when something is wired and chosen that could do the work. */
    public boolean isAvailable() {
        return provider() != null;
    }

    /** Name of the chosen provider, or {@code null} when there is none. */
    public @Nullable String providerName() {
        TranslationProvider chosen = provider();
        return chosen == null ? null : chosen.name();
    }

    /**
     * Translates one article.
     *
     * @return {@code true} when a translation was stored
     */
    public boolean translate(ArticleDocument article, Instant now) {
        TranslationProvider provider = provider();
        if (provider == null) {
            return false;
        }
        String pivot = TextCleaner.normalizeLanguage(config.pivotLanguage().value());
        if (pivot == null) {
            return false;
        }
        String articleId = StringUtils.defaultString(article.getId());

        String title = TextCleaner.truncate(article.getTitle(), config.maxSourceChars().value());
        if (StringUtils.isBlank(title)) {
            // Nothing to translate and nothing a retry would change.
            articleService.recordTranslationFailure(articleId,
                    "article has no title to translate", config.maxAttempts().value(), now);
            return false;
        }
        String summary = config.translateSummary().value()
                        && StringUtils.isNotBlank(article.getSummary())
                ? TextCleaner.truncate(article.getSummary(), config.maxSourceChars().value())
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
                    .model(translated.getModel())
                    .language(pivot)
                    .createdAt(now)
                    .content(content)
                    .build());
            // The enrichment is the record; these two also go onto the
            // article, because MongoDB allows one text index per collection
            // and searchable text has to live on the document being
            // searched. Without them a German query finds nothing in a
            // German-facing archive.
            articleService.recordTranslated(articleId,
                    translated.getTitle(), translated.getSummary());

            log.debug("Translated article {} into '{}' via {}", articleId, pivot, provider.name());
            return true;
        } catch (TranslationException e) {
            // A permanent failure is charged the whole budget at once:
            // retrying a rejected token five times only produces five
            // rejections, and the queue should say so and move on.
            int attempts = e.isRetryable()
                    ? article.getTranslationAttempts()
                    : config.maxAttempts().value();
            articleService.recordTranslationFailure(articleId, e.getMessage(), attempts, now);
            log.info("Translation of article {} failed ({}): {}", articleId,
                    e.isRetryable() ? "will retry" : "giving up", e.getMessage());
            return false;
        }
    }
}
