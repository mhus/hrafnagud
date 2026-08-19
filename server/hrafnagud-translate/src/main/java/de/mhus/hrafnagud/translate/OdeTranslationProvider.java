package de.mhus.hrafnagud.translate;

import de.mhus.vance.ode.core.VanceOdeException;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

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
 * <p>Built by {@link TranslateConfiguration} only when Ode is
 * configured. Without {@code vance.ode.base-url} there is no client bean,
 * no provider, and the backlog simply goes unworked.
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
    public String translate(String text, String targetLanguage) {
        try {
            String translated = events.requireText(eventName,
                    Map.of("text", text, "targetLang", targetLanguage));
            if (translated.isBlank()) {
                // A blank result for non-blank input is the event answering
                // something other than a translation. Storing it would put
                // an empty title in the archive and call it translated.
                throw TranslationException.permanent(
                        "event '" + eventName + "' returned a blank translation", null);
            }
            return translated;
        } catch (VanceOdeException e) {
            // Ode already decided whether the far end might behave
            // differently next time; re-deriving that from the status here
            // would be a second, divergent opinion.
            throw new TranslationException(
                    "event '" + eventName + "' failed: " + e.getMessage(), e.isRetryable(), e);
        }
    }
}
