package de.mhus.hrafnagud.hugin.gemini;

import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.hugin.translate.TranslationProvider;
import de.mhus.hrafnagud.settings.Settings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Gemini provider when there is a key for it.
 *
 * <p>A blank key means no provider, the same reading a blank brain address
 * gets: the bean is {@code null} and the translation service reports itself
 * without that option rather than failing on the first article. Nothing else
 * here is conditional — the model, the temperature and the timeout are
 * settings, so they can change without this class being consulted again.
 */
@Configuration
@Slf4j
public class GeminiConfiguration {

    @Bean
    public @Nullable TranslationProvider geminiTranslationProvider(
            HuginProperties properties, Settings settings) {

        String apiKey = properties.getGemini().getApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.debug("No hugin.gemini.apiKey — Gemini translation provider not wired");
            return null;
        }
        log.info("Gemini translation provider wired (model '{}')",
                settings.getGemini().model().value());
        return new GeminiTranslationProvider(apiKey.trim(), settings.getGemini());
    }
}
