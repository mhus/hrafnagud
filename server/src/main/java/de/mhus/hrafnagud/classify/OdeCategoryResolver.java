package de.mhus.hrafnagud.classify;

import de.mhus.hrafnagud.api.category.CategoryMappingStatus;
import de.mhus.hrafnagud.munin.category.CategoryResolver;
import de.mhus.vance.ode.ursa.EventResult;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Stage two over a Vancetope brain: one event per unresolved category.
 *
 * <p>The same shape as the translation provider, deliberately — event out,
 * structured answer back, prompt and model living in a kit on the brain side so
 * they can be changed without redeploying this service.
 *
 * <p>What it sends is a <b>string</b>, not an article: the unit of work here is
 * a category, resolved once for the whole archive. That is what makes the model
 * affordable for this and not for translating every article.
 *
 * <p>Three answers are expected and all three are decisions:
 * {@code topic} with a Media Topic qcode, {@code not_a_topic} for a format, a
 * person or a product, and {@code place} for a country or a city — the last
 * because a publisher's category list is full of places and throwing that away
 * would lose the best evidence available about what an article is about.
 */
@Slf4j
public class OdeCategoryResolver implements CategoryResolver {

    private final UrsaEventClient events;
    private final String eventName;

    public OdeCategoryResolver(UrsaEventClient events, String eventName) {
        this.events = events;
        this.eventName = eventName;
    }

    @Override
    public String name() {
        return "vance-ode";
    }

    @Override
    public Decision resolve(String raw, @Nullable String candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", raw);
        payload.put("candidate", StringUtils.defaultString(candidate));

        EventResult result = events.fire(eventName, payload);
        Map<String, Object> output = result.getOutput();
        if (output == null) {
            // The event answered with nothing. Almost always a configuration
            // mistake on the brain side — async: true, or an output the caller
            // may not see — so it is thrown rather than recorded as a verdict
            // about the category.
            throw new IllegalStateException("event '" + eventName
                    + "' returned no output; is it async or withholding it?");
        }

        String kind = text(output, "kind").toLowerCase(Locale.ROOT);
        String note = StringUtils.trimToNull(text(output, "note"));
        return switch (kind) {
            case "topic" -> {
                String topicId = text(output, "topicId");
                if (topicId.isBlank()) {
                    throw new IllegalStateException("event '" + eventName
                            + "' answered 'topic' without a topicId");
                }
                yield Decision.topic(topicId, note);
            }
            case "not_a_topic" -> Decision.notATopic(note);
            case "place" -> Decision.place(note);
            // An unrecognised kind is a contract mismatch, not a category
            // problem: recording it as a verdict would bury a broken kit in
            // the data.
            default -> throw new IllegalStateException("event '" + eventName
                    + "' answered with unknown kind '" + kind + "'");
        };
    }

    private static String text(Map<String, Object> output, String key) {
        Object value = output.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
