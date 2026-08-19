package de.mhus.hrafnagud.translate;

import de.mhus.vance.ode.core.VanceOdeException;
import de.mhus.vance.ode.ursa.EventResult;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Translates by firing a Vancetope event.
 *
 * <p>The brain side is a script event that calls a model through a recipe
 * — see the {@code translation} kit. That matters for what this class is
 * <em>not</em>: it holds no prompt, no model name and no notion of how a
 * translation is produced. Those live in documents an operator can edit
 * without a redeploy here, which is the whole reason to integrate through
 * an event rather than to call a model directly.
 *
 * <p>Built by {@link TranslateConfiguration} only when Ode is configured.
 */
@Slf4j
public class OdeTranslationProvider implements TranslationProvider {

    private final UrsaEventClient events;
    private final String eventName;

    public OdeTranslationProvider(UrsaEventClient events, String eventName) {
        this.events = events;
        this.eventName = eventName;
    }

    @Override
    public String name() {
        return "vance-ode";
    }

    @Override
    public TranslatedText translate(String title, @Nullable String summary,
            String targetLanguage) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("summary", StringUtils.defaultString(summary));
        payload.put("targetLang", targetLanguage);

        try {
            EventResult result = events.fire(eventName, payload);
            Map<String, Object> output = result.getOutput();
            if (output == null) {
                // The event answered, but with nothing. Almost always a
                // configuration mistake on the brain side — async: true, or
                // an output the caller is not allowed to see.
                throw TranslationException.permanent("event '" + eventName
                        + "' returned no output; is it async or withholding it?", null);
            }

            String translatedTitle = text(output, "title");
            if (translatedTitle.isBlank()) {
                // A blank title for a non-blank one is the event answering
                // something other than a translation. Storing it would put
                // an empty headline in the archive and call it translated.
                throw TranslationException.permanent(
                        "event '" + eventName + "' returned no title", null);
            }
            return new TranslatedText(translatedTitle,
                    StringUtils.trimToNull(text(output, "summary")));

        } catch (VanceOdeException e) {
            // Ode already decided whether the far end might behave
            // differently next time; re-deriving that from the status here
            // would be a second, divergent opinion.
            throw new TranslationException(
                    "event '" + eventName + "' failed: " + e.getMessage(), e.isRetryable(), e);
        }
    }

    private static String text(Map<String, Object> output, String key) {
        Object value = output.get(key);
        return value instanceof String s ? s.trim() : "";
    }
}
