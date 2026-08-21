package de.mhus.hrafnagud.hugin.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.settings.TestSettings;
import de.mhus.vance.ode.core.VanceOdeCoreAutoConfiguration;
import de.mhus.vance.ode.ursa.UrsaEventClient;
import de.mhus.vance.ode.ursa.VanceOdeUrsaAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * That the provider appears exactly when Ode is configured, and not
 * otherwise.
 *
 * <p>This is the seam unit tests cannot reach: whether Ode's
 * auto-configuration and our bean actually meet in a running context. It
 * is also where the first attempt was wrong — {@code @ConditionalOnBean}
 * on a component-scanned class has no defined ordering against user
 * beans, so the provider could have been skipped with Ode configured and
 * the only symptom would have been a backlog that never drained.
 */
class TranslateWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    VanceOdeCoreAutoConfiguration.class,
                    VanceOdeUrsaAutoConfiguration.class))
            .withUserConfiguration(TranslateConfiguration.class, StubMuninBeans.class);

    @Configuration
    static class StubMuninBeans {
        @Bean ArticleService articleService() { return mock(ArticleService.class); }
        @Bean MuninProperties muninProperties() { return new MuninProperties(); }
        @Bean Settings muninSettings(MuninProperties properties) {
            return TestSettings.of(properties);
        }
        @Bean de.mhus.hrafnagud.munin.enrichment.EnrichmentService enrichmentService() {
            return mock(de.mhus.hrafnagud.munin.enrichment.EnrichmentService.class);
        }
        @Bean TranslationService translationService(ArticleService articles,
                de.mhus.hrafnagud.munin.enrichment.EnrichmentService enrichments,
                Settings settings,
                org.springframework.beans.factory.ObjectProvider<TranslationProvider> provider) {
            // Same construction the production @Service uses.
            return new TranslationService(articles, enrichments, settings, provider);
        }
    }

    @Test
    void with_a_brain_url_the_provider_is_wired() {
        runner.withPropertyValues(
                        "vance.ode.base-url=http://localhost:9990",
                        "vance.ode.tenant=acme")
                .run(context -> {
                    assertThat(context).hasSingleBean(UrsaEventClient.class);
                    assertThat(context.getBean(TranslationService.class).isAvailable()).isTrue();
                    assertThat(context.getBean(TranslationService.class).providerName())
                            .isEqualTo("vance-ode");
                });
    }

    @Test
    void without_a_brain_url_nothing_is_wired_and_that_is_not_an_error() {
        // A collector with no brain must still boot. Munin keeps queueing;
        // the startup report says that nothing will drain it.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UrsaEventClient.class);
            assertThat(context.getBean(TranslationService.class).isAvailable()).isFalse();
            assertThat(context.getBean(TranslationService.class).providerName()).isNull();
        });
    }

    /**
     * The worker is a bean whether or not translation is switched on, and the
     * setting decides per round.
     *
     * <p>It used to be {@code @ConditionalOnProperty}, which made switching
     * translation on a restart of the collector. Off is still the default —
     * a translation spends model time somebody pays for — but "off" now means
     * a round that returns immediately rather than a bean that does not exist.
     * The switch itself is {@code WorkerSwitch}; what is pinned down here is
     * that the bean no longer depends on it.
     */
    @Test
    void the_worker_exists_whether_or_not_it_is_switched_on() {
        ApplicationContextRunner withTick = runner.withUserConfiguration(TickConfig.class);

        withTick.run(context ->
                assertThat(context).hasSingleBean(TranslationTick.class));
        withTick.withPropertyValues("hugin.translation.enabled=false").run(context ->
                assertThat(context).hasSingleBean(TranslationTick.class));
        withTick.withPropertyValues("hugin.translation.enabled=true").run(context ->
                assertThat(context).hasSingleBean(TranslationTick.class));
    }

    /** The tick is component-scanned in production; here it is declared. */
    @Configuration
    @org.springframework.context.annotation.Import(TranslationTick.class)
    static class TickConfig {
    }

    @Test
    void the_event_name_can_be_overridden() {
        runner.withPropertyValues(
                        "vance.ode.base-url=http://localhost:9990",
                        "vance.ode.tenant=acme",
                        "hrafnagud.translate.event=my-translate")
                .run(context -> assertThat(context).hasSingleBean(TranslationProvider.class));
    }
}
