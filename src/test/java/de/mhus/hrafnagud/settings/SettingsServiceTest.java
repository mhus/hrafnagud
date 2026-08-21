package de.mhus.hrafnagud.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.config.HuginProperties;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two layers and the traffic between them: a setting reads its configured
 * default until an override is stored, and a handle that is already in
 * somebody's field sees the change.
 */
class SettingsServiceTest {

    @Test
    void without_an_override_a_setting_reads_the_configured_default() {
        MuninProperties properties = new MuninProperties();
        properties.getFeed().setBatchSize(7);

        Settings settings = TestSettings.of(properties);

        assertThat(settings.getFeed().batchSize().value()).isEqualTo(7);
        assertThat(settings.getFeed().batchSize().overridden()).isFalse();
    }

    @Test
    void a_stored_override_wins_over_the_configuration() {
        MuninProperties properties = new MuninProperties();
        properties.getFeed().setBatchSize(7);

        Settings settings =
                TestSettings.build(properties, Map.of("munin.feed.batchSize", "42"));

        assertThat(settings.getFeed().batchSize().value()).isEqualTo(42);
        assertThat(settings.getFeed().batchSize().defaultValue()).isEqualTo(7);
        assertThat(settings.getFeed().batchSize().overridden()).isTrue();
    }

    @Test
    void every_type_is_read_back_as_itself() {
        Settings settings = TestSettings.with(Map.of(
                "munin.feed.enabled", "false",
                "munin.feed.claimLease", "PT2M",
                "munin.http.maxBodyBytes", "1048576",
                "munin.category.acceptConfidence", "0.75",
                "hugin.translation.pivotLanguage", "de"));

        assertThat(settings.getFeed().enabled().value()).isFalse();
        assertThat(settings.getFeed().claimLease().value()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.getHttp().maxBodyBytes().value()).isEqualTo(1_048_576L);
        assertThat(settings.getCategory().acceptConfidence().value()).isEqualTo(0.75);
        assertThat(settings.getTranslation().pivotLanguage().value()).isEqualTo("de");
    }

    @Test
    void a_language_list_is_read_as_a_set_of_normalised_tags() {
        Settings settings =
                TestSettings.with("hugin.translation.readableLanguages", "en, DE, fr-FR");

        assertThat(settings.getTranslation().readableLanguages().value())
                .containsExactly("en", "de", "fr");
    }

    /**
     * What the console shows has to be what it can hand back on save, which
     * for a set means a joined list rather than {@code [en, de]}.
     */
    @Test
    void a_language_list_renders_as_the_text_it_was_written_as() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(),
                Map.of("hugin.translation.readableLanguages", "en,de"));
        Setting<?> setting = fixture.store().require("hugin.translation.readableLanguages");

        assertThat(setting.render()).isEqualTo("en,de");

        fixture.store().set(setting.key(), setting.render());
        assertThat(fixture.store().require(setting.key()).render()).isEqualTo("en,de");
    }

    /**
     * A typo here has no visible effect other than a translation bill, so it
     * is refused rather than dropped.
     */
    @Test
    void something_that_is_not_a_language_tag_is_refused() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        assertThatThrownBy(() -> fixture.store()
                .set("hugin.translation.readableLanguages", "en,german"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("german");
    }

    /** The point of the handle: no rebuild, no listener, no restart. */
    @Test
    void a_handle_already_held_sees_a_later_change() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());
        SettingBoolean enabled = fixture.settings().getContent().enabled();
        assertThat(enabled.value()).isFalse();

        fixture.store().set("munin.content.enabled", "true");

        assertThat(enabled.value()).isTrue();
    }

    @Test
    void a_reset_returns_the_setting_to_its_configured_default() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(),
                Map.of("munin.feed.batchSize", "42"));
        SettingInt batchSize = fixture.settings().getFeed().batchSize();
        assertThat(batchSize.value()).isEqualTo(42);

        fixture.store().reset("munin.feed.batchSize");

        assertThat(batchSize.value()).isEqualTo(new MuninProperties().getFeed().getBatchSize());
        assertThat(batchSize.overridden()).isFalse();
    }

    @Test
    void a_value_of_the_wrong_type_is_refused_rather_than_stored() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        assertThatThrownBy(() -> fixture.store().set("munin.feed.claimLease", "10 minutes"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DURATION");
        assertThatThrownBy(() -> fixture.store().set("munin.feed.batchSize", "twenty"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fixture.store().set("munin.feed.enabled", "yes"))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * A number that parses is not the same as a number a worker can use, and
     * the difference is not academic: zero is the value that turns a worker off
     * while every switch still reads "on".
     */
    @Test
    void a_count_of_zero_or_less_is_refused_rather_than_stored() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        // Claims zero sources per round, for ever, and WorkerSwitch says on.
        assertThatThrownBy(() -> fixture.store().set("munin.feed.batchSize", "0"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("positive");
        // Truncates every title to nothing, and the article is then charged its
        // whole attempt budget for having no title to translate.
        assertThatThrownBy(() -> fixture.store().set("hugin.translation.maxSourceChars", "0"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fixture.store().set("munin.content.maxAttempts", "-1"))
                .isInstanceOf(BadRequestException.class);
        // A byte cap of zero is not a strict limit, it is a fetcher that
        // rejects everything while looking configured.
        assertThatThrownBy(() -> fixture.store().set("munin.http.maxBodyBytes", "0"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void a_fraction_outside_zero_to_one_is_refused() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        assertThatThrownBy(() -> fixture.store().set("munin.category.acceptConfidence", "1.5"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("fraction");
        assertThatThrownBy(() -> fixture.store().set("hugin.gemini.temperature", "-0.1"))
                .isInstanceOf(BadRequestException.class);
        // Both ends are meaningful: accept every match, sample deterministically.
        fixture.store().set("munin.category.acceptConfidence", "0");
        assertThat(fixture.settings().getCategory().acceptConfidence().value()).isZero();
    }

    /**
     * A blank write is a reset that does not look like one. Refusing it keeps
     * the two gestures apart — see {@code SettingsService.set}.
     */
    @Test
    void a_blank_value_is_refused_and_points_at_delete() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        assertThatThrownBy(() -> fixture.store().set("hugin.translation.pivotLanguage", "  "))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DELETE");
    }

    @Test
    void an_undeclared_key_cannot_be_read_or_written() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        assertThatThrownBy(() -> fixture.store().require("munin.feed.batchsize"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> fixture.store().set("munin.invented.knob", "3"))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * An override edited straight into the database bypasses the write-time
     * check, so the read has to survive it. The alternative is a worker dying
     * on a parse error every round.
     */
    @Test
    void an_unparsable_stored_value_falls_back_to_the_default() {
        Settings settings =
                TestSettings.with(Map.of("munin.feed.claimLease", "ten minutes"));

        assertThat(settings.getFeed().claimLease().value())
                .isEqualTo(new MuninProperties().getFeed().getClaimLease());
    }

    /**
     * The startup order this has to survive: the first load runs from
     * {@code SettingsService}'s own {@code @PostConstruct}, before
     * {@link Settings} declares anything, and every following poll returns
     * early because the values have not changed. So the one case the check
     * exists for — an override left behind by a rename, sitting there from the
     * start — is the one it used to miss entirely.
     */
    @Test
    void an_override_stored_before_the_declarations_is_still_reported() {
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                SettingDocument.builder().key("munin.feed.batchSize").value("42").build(),
                SettingDocument.builder().key("munin.renamed.knob").value("3").build()));

        SettingsService store = new SettingsService(repository);
        store.load();
        // Nothing declared yet, so nothing may look unknown here.
        assertThat(store.undeclaredOverrides()).isEmpty();

        new Settings(store, new MuninProperties(), new HuginProperties());

        assertThat(store.undeclaredOverrides()).containsExactly("munin.renamed.knob");
    }

    @Test
    void declaring_one_key_twice_fails_loudly() {
        TestSettings.Fixture fixture = TestSettings.fixture(new MuninProperties(), Map.of());

        assertThatThrownBy(() -> fixture.store().integer("munin.feed.batchSize", () -> 1, "again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declared twice");
    }
}
