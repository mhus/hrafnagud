package de.mhus.hrafnagud.hugin.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.mhus.hrafnagud.hugin.translate.TranslatedText;
import de.mhus.hrafnagud.hugin.translate.TranslationException;
import de.mhus.hrafnagud.hugin.translate.TranslationProvider;
import de.mhus.hrafnagud.settings.Settings;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Translates by calling Google's Gemini API directly.
 *
 * <p>The other half of the choice: {@code OdeTranslationProvider} asks a
 * Vancetope brain, which owns the prompt and the model, and this one owns both
 * itself. Which of them runs is {@code hugin.translation.provider} — a setting,
 * so the same articles can be put through both and the results compared out of
 * {@code enrichments}, where each one records the model that produced it.
 *
 * <p>What the brain path buys is that the prompt is editable without a
 * deployment; what this path buys is one hop instead of three. For an archive
 * translating headlines at volume the second matters, which is the whole reason
 * this exists.
 *
 * <h2>The prompt lives here, and that is the trade</h2>
 * A prompt in compiled code is a prompt that needs a release to change. It is
 * kept deliberately short — the instruction is nearly all rules, because the
 * failure modes of a translating model are summarising, commenting and
 * politeness the original did not have. The <em>schema</em> does the rest:
 * Gemini is asked for a response matching a two-field object, so there is no
 * markdown fence to strip and no prose to parse around.
 */
@Slf4j
public class GeminiTranslationProvider implements TranslationProvider {

    /** Recorded on every result, and the value {@code hugin.translation.provider} selects. */
    public static final String NAME = "gemini";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What Gemini must answer with. Two fields, both required — a model that
     * returns only the title for an article without a teaser is a case the
     * caller would otherwise have to guess at.
     */
    private static final ResponseFormat SCHEMA = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("translation")
                    .rootElement(JsonObjectSchema.builder()
                            .addStringProperty("title", "the translated title")
                            .addStringProperty("summary",
                                    "the translated teaser, or an empty string")
                            .required("title", "summary")
                            .build())
                    .build())
            .build();

    private final String apiKey;
    private final Settings.Gemini config;
    private final ChatModelFactory factory;

    /**
     * How a client is built from the three settings that shape it. An
     * interface so the request can be tested without a network — the
     * production implementation is {@link #google()}.
     */
    interface ChatModelFactory {
        ChatModel create(String apiKey, String model, double temperature, Duration timeout);
    }

    /** The values a client is built from; a change to any of them rebuilds it. */
    private record Shape(String model, double temperature, Duration timeout) {
    }

    private volatile @Nullable Shape shape;
    private volatile @Nullable ChatModel client;

    public GeminiTranslationProvider(String apiKey, Settings.Gemini config) {
        this(apiKey, config, google());
    }

    GeminiTranslationProvider(String apiKey, Settings.Gemini config, ChatModelFactory factory) {
        this.apiKey = apiKey;
        this.config = config;
        this.factory = factory;
    }

    static ChatModelFactory google() {
        return (apiKey, model, temperature, timeout) -> GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .timeout(timeout)
                .responseFormat(SCHEMA)
                // One attempt per call. Retrying inside the client would hide
                // a rate limit from the queue, which is the one thing that has
                // to reach it: the queue can wait minutes, a client cannot.
                .maxRetries(1)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * The client for the settings as they stand, rebuilt when they change.
     *
     * <p>The model name is a setting because it is what an experiment turns,
     * and a client holds it — so the two have to be reconciled somewhere. Here
     * rather than at every call, because building one parses no configuration
     * but does allocate an HTTP client.
     */
    private ChatModel client() {
        Shape wanted = new Shape(config.model().value(), config.temperature().value(),
                config.timeout().value());
        ChatModel current = client;
        if (current != null && wanted.equals(shape)) {
            return current;
        }
        ChatModel fresh = factory.create(apiKey, wanted.model(), wanted.temperature(),
                wanted.timeout());
        client = fresh;
        shape = wanted;
        log.info("Gemini client built for model '{}' (temperature {}, timeout {})",
                wanted.model(), wanted.temperature(), wanted.timeout());
        return fresh;
    }

    @Override
    public TranslatedText translate(String title, @Nullable String summary,
            String targetLanguage) {

        String prompt = prompt(title, summary, targetLanguage);
        ChatResponse response;
        try {
            response = client().chat(dev.langchain4j.data.message.UserMessage.from(prompt));
        } catch (NonRetriableException e) {
            // A rejected key, an unknown model, a prompt the safety filter
            // refuses: asking again produces the same answer.
            throw TranslationException.permanent(
                    "gemini refused the request: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Everything else — timeouts, 5xx, rate limits — is worth another
            // round. langchain4j has already classified these; re-deriving the
            // judgement from a message string is how it gets it wrong.
            throw TranslationException.transient_(
                    "gemini call failed: " + e.getMessage(), e);
        }

        String answer = response.aiMessage() == null ? null : response.aiMessage().text();
        if (StringUtils.isBlank(answer)) {
            throw TranslationException.transient_(
                    "gemini answered with nothing", null);
        }
        return read(answer, response.modelName());
    }

    /**
     * Turns the answer into a result.
     *
     * <p>Unparsable JSON is treated as retryable: the schema makes it unlikely
     * and a different sampling of the same prompt usually produces valid JSON,
     * so one more round is cheaper than losing the article. A <em>blank
     * title</em> is not — that is the model answering the wrong question, and
     * it would answer it again.
     */
    private TranslatedText read(String answer, @Nullable String model) {
        JsonNode json;
        try {
            json = JSON.readTree(answer);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw TranslationException.transient_(
                    "gemini answered with something that is not JSON: "
                            + StringUtils.abbreviate(answer, 200), e);
        }
        String title = text(json, "title");
        if (StringUtils.isBlank(title)) {
            throw TranslationException.permanent(
                    "gemini returned no title", null);
        }
        return new TranslatedText(title, StringUtils.trimToNull(text(json, "summary")), model);
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    /**
     * The instruction. Nearly all rules, because what a translating model does
     * wrong is not the language — it is summarising, commenting, and adding
     * politeness the original does not have.
     */
    private static String prompt(String title, @Nullable String summary, String targetLanguage) {
        return """
                Translate the title and teaser below into the target language.

                Rules:
                - Translate. Do not summarise, shorten or comment. A program \
                reads this, not a person.
                - Keep proper nouns, numbers, dates and units as they are.
                - Keep the register: a headline stays a headline, a news lede \
                stays a news lede. Do not add politeness the original lacks.
                - A field already in the target language comes back unchanged.
                - An empty teaser comes back as an empty string. Do not invent \
                one from the title.

                Target language: %s

                Title: %s

                Teaser: %s"""
                .formatted(targetLanguage, title, StringUtils.defaultString(summary));
    }
}
