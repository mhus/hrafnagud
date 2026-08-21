package de.mhus.hrafnagud.hugin.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.settings.TestSettings;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Which provider does the work, when there is more than one.
 *
 * <p>The point of the setting: the same articles can go through a brain and
 * through a model directly, and the comparison is a settings change rather than
 * a deployment. The interesting cases are the ones where the answer is
 * <em>nothing</em> — two ways to spend money with no instruction which to use
 * is a question, not a default.
 */
class TranslationProviderChoiceTest {

    private static final class Stub implements TranslationProvider {
        private final String name;

        Stub(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TranslatedText translate(String title, @Nullable String summary, String target) {
            return new TranslatedText(title, summary, name + "-model");
        }
    }

    private TranslationService serviceWith(Map<String, String> overrides,
            TranslationProvider... providers) {
        return new TranslationService(
                mock(ArticleService.class), mock(EnrichmentService.class),
                TestSettings.build(new MuninProperties(), new HuginProperties(), overrides),
                TranslationServiceTest.objectProviderOf(providers));
    }

    @Test
    void with_one_provider_and_no_instruction_that_one_is_used() {
        TranslationService service = serviceWith(Map.of(), new Stub("vance-ode"));

        assertThat(service.isAvailable()).isTrue();
        assertThat(service.providerName()).isEqualTo("vance-ode");
    }

    @Test
    void with_two_providers_and_no_instruction_nothing_is_used() {
        // Not "the first one": that would spend money the operator did not
        // ask to spend, and which of the two would depend on bean order.
        TranslationService service =
                serviceWith(Map.of(), new Stub("vance-ode"), new Stub("gemini"));

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.providerName()).isNull();
    }

    @Test
    void the_setting_picks_one_of_several() {
        TranslationService service = serviceWith(
                Map.of("hugin.translation.provider", "gemini"),
                new Stub("vance-ode"), new Stub("gemini"));

        assertThat(service.providerName()).isEqualTo("gemini");
    }

    @Test
    void the_name_is_matched_regardless_of_case() {
        TranslationService service = serviceWith(
                Map.of("hugin.translation.provider", "Gemini"), new Stub("gemini"));

        assertThat(service.providerName()).isEqualTo("gemini");
    }

    /**
     * A name nothing answers to is not a reason to use something else — an
     * operator who asked for one engine and silently got another would compare
     * the wrong two runs.
     */
    @Test
    void a_name_that_is_not_wired_means_nothing_translates() {
        TranslationService service = serviceWith(
                Map.of("hugin.translation.provider", "deepl"),
                new Stub("vance-ode"), new Stub("gemini"));

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void switching_the_setting_switches_the_provider_without_a_restart() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(),
                new HuginProperties(), Map.of("hugin.translation.provider", "vance-ode"));
        TranslationService service = new TranslationService(
                mock(ArticleService.class), mock(EnrichmentService.class), fixture.settings(),
                TranslationServiceTest.objectProviderOf(
                        new Stub("vance-ode"), new Stub("gemini")));
        assertThat(service.providerName()).isEqualTo("vance-ode");

        fixture.store().set("hugin.translation.provider", "gemini");

        assertThat(service.providerName()).isEqualTo("gemini");
    }

    @Test
    void with_nothing_wired_there_is_nothing_to_choose() {
        TranslationService service = serviceWith(Map.of());

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.providerName()).isNull();
    }
}
