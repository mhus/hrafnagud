package de.mhus.hrafnagud.hugin.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.hugin.translate.TranslatedText;
import de.mhus.hrafnagud.hugin.translate.TranslationException;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.settings.TestSettings;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * What the provider does with what a model answers.
 *
 * <p>No network: the client is built through a factory the test supplies, so
 * these are the answers a model can give — including the ones it should not.
 */
class GeminiTranslationProviderTest {

    /** Every request the fake client saw, so the prompt can be inspected. */
    private final List<String> prompts = new ArrayList<>();

    private GeminiTranslationProvider provider(Function<String, ChatResponse> answer) {
        return provider(TestSettings.defaults(), answer);
    }

    private GeminiTranslationProvider provider(Settings settings,
            Function<String, ChatResponse> answer) {
        return new GeminiTranslationProvider("test-key", settings.getGemini(),
                (apiKey, model, temperature, timeout) -> new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        String prompt = request.messages().getLast().toString();
                        prompts.add(prompt);
                        return answer.apply(prompt);
                    }
                });
    }

    private static ChatResponse answering(String text, String modelName) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder().modelName(modelName).build())
                .build();
    }

    @Test
    void a_json_answer_becomes_a_result_that_names_the_model() {
        GeminiTranslationProvider provider = provider(prompt -> answering(
                "{\"title\": \"Rat beschliesst Plan\", \"summary\": \"Die Abstimmung.\"}",
                "gemini-3.5-flash-lite"));

        TranslatedText result =
                provider.translate("Council approves plan", "The vote ended it.", "de");

        assertThat(result.getTitle()).isEqualTo("Rat beschliesst Plan");
        assertThat(result.getSummary()).isEqualTo("Die Abstimmung.");
        // Per result, not per provider — a fallback chain answers with
        // different models on different calls, and the archive stores which.
        assertThat(result.getModel()).isEqualTo("gemini-3.5-flash-lite");
    }

    @Test
    void the_target_language_and_both_fields_reach_the_model() {
        GeminiTranslationProvider provider =
                provider(prompt -> answering("{\"title\":\"x\",\"summary\":\"\"}", "m"));

        provider.translate("Council approves plan", "The vote ended it.", "ja");

        assertThat(prompts).hasSize(1);
        assertThat(prompts.getFirst())
                .contains("Target language: ja")
                .contains("Council approves plan")
                .contains("The vote ended it.");
    }

    @Test
    void an_empty_teaser_comes_back_as_no_teaser() {
        GeminiTranslationProvider provider = provider(prompt ->
                answering("{\"title\": \"Titel\", \"summary\": \"\"}", "m"));

        assertThat(provider.translate("Title", null, "de").getSummary()).isNull();
    }

    /**
     * The schema makes it unlikely, and a different sampling of the same
     * prompt usually produces valid JSON — so one more round is cheaper than
     * losing the article.
     */
    @Test
    void something_that_is_not_json_is_worth_another_round() {
        GeminiTranslationProvider provider =
                provider(prompt -> answering("Sure! Here is your translation:", "m"));

        assertThatThrownBy(() -> provider.translate("Title", null, "de"))
                .isInstanceOf(TranslationException.class)
                .hasMessageContaining("not JSON")
                .extracting(e -> ((TranslationException) e).isRetryable()).isEqualTo(true);
    }

    /**
     * A missing title is the model answering the wrong question, and it would
     * answer it again. Retrying that only spends tokens.
     */
    @Test
    void an_answer_without_a_title_is_final() {
        GeminiTranslationProvider provider =
                provider(prompt -> answering("{\"title\": \"\", \"summary\": \"x\"}", "m"));

        assertThatThrownBy(() -> provider.translate("Title", null, "de"))
                .isInstanceOf(TranslationException.class)
                .extracting(e -> ((TranslationException) e).isRetryable()).isEqualTo(false);
    }

    @Test
    void a_rejected_key_is_final_and_a_rate_limit_is_not() {
        GeminiTranslationProvider rejected = provider(prompt -> {
            throw new AuthenticationException("API key not valid");
        });
        assertThatThrownBy(() -> rejected.translate("Title", null, "de"))
                .isInstanceOf(TranslationException.class)
                .extracting(e -> ((TranslationException) e).isRetryable()).isEqualTo(false);

        GeminiTranslationProvider throttled = provider(prompt -> {
            throw new RateLimitException("429");
        });
        assertThatThrownBy(() -> throttled.translate("Title", null, "de"))
                .isInstanceOf(TranslationException.class)
                .extracting(e -> ((TranslationException) e).isRetryable()).isEqualTo(true);
    }

    /**
     * The model name is a setting because it is what an experiment turns, and
     * a client holds it — so changing the setting has to reach the client.
     */
    @Test
    void changing_the_model_setting_rebuilds_the_client() {
        TestSettings.Fixture fixture =
                TestSettings.fixture(new MuninProperties(), new HuginProperties(), Map.of());
        List<String> built = new ArrayList<>();
        GeminiTranslationProvider provider = new GeminiTranslationProvider(
                "test-key", fixture.settings().getGemini(),
                (apiKey, model, temperature, timeout) -> {
                    built.add(model);
                    return new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            return answering("{\"title\":\"t\",\"summary\":\"\"}", model);
                        }
                    };
                });

        assertThat(provider.translate("Title", null, "de").getModel())
                .isEqualTo(new HuginProperties().getGemini().getModel());
        // Same settings: the client is reused rather than rebuilt per article.
        provider.translate("Title", null, "de");
        assertThat(built).hasSize(1);

        fixture.store().set("hugin.gemini.model", "gemini-3.7-flash");

        assertThat(provider.translate("Title", null, "de").getModel())
                .isEqualTo("gemini-3.7-flash");
        assertThat(built).containsExactly(
                new HuginProperties().getGemini().getModel(), "gemini-3.7-flash");
    }

    @Test
    void the_timeout_and_temperature_come_from_the_settings() {
        Settings settings = TestSettings.with(Map.of(
                "hugin.gemini.temperature", "0.4",
                "hugin.gemini.timeout", "PT30S"));
        List<String> shapes = new ArrayList<>();
        GeminiTranslationProvider provider = new GeminiTranslationProvider(
                "k", settings.getGemini(),
                (apiKey, model, temperature, timeout) -> {
                    shapes.add(temperature + "/" + timeout);
                    return new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            return answering("{\"title\":\"t\",\"summary\":\"\"}", model);
                        }
                    };
                });

        provider.translate("Title", null, "de");

        assertThat(shapes).containsExactly("0.4/" + Duration.ofSeconds(30));
    }
}
