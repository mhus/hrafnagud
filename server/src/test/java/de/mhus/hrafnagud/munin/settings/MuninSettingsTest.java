package de.mhus.hrafnagud.munin.settings;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.setting.SettingType;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Properties of the declaration list itself.
 *
 * <p>Not tests of behaviour but of the contract the settings screen depends
 * on: every key is one an operator can find in {@code application.yml}, and
 * every one of them says what it does. Both are the kind of thing that decays
 * one hurried entry at a time.
 */
class MuninSettingsTest {

    private final TestSettings.Fixture fixture =
            TestSettings.fixture(new MuninProperties(), Map.of());

    @Test
    void every_declared_key_is_a_munin_property_name() {
        List<Setting<?>> declared = fixture.store().declared();

        assertThat(declared).isNotEmpty();
        assertThat(declared).allSatisfy(setting ->
                assertThat(setting.key()).startsWith("munin."));
    }

    @Test
    void every_setting_says_what_it_does() {
        assertThat(fixture.store().declared()).allSatisfy(setting -> {
            assertThat(setting.description()).as("description of %s", setting.key()).isNotBlank();
            // A description that is one word longer than the key is not one.
            assertThat(setting.description().length())
                    .as("description of %s is a sentence", setting.key())
                    .isGreaterThan(20);
        });
    }

    /**
     * The default has to be readable without a database, because that is the
     * state a fresh installation runs in and the state the console renders as
     * "from the configuration".
     */
    @Test
    void every_setting_resolves_its_default_without_an_override() {
        assertThat(fixture.store().declared()).allSatisfy(setting -> {
            assertThat(setting.value()).as("value of %s", setting.key()).isNotNull();
            assertThat(setting.overridden()).isFalse();
            if (setting.type() != SettingType.STRING
                    && setting.type() != SettingType.STRING_LIST) {
                // A number, a switch and a duration always have a usable
                // default. "Nothing configured" is only a meaningful default
                // for text — the pivot language and the readable-language
                // list, whose emptiness is how translation stays dormant.
                assertThat(setting.render()).as("rendered %s", setting.key()).isNotBlank();
            }
        });
    }

    /**
     * Round-trip: what the API shows must be a value the API accepts back. A
     * duration rendered as "PT30S" and parsed as "PT30S" is the same value; a
     * duration rendered as "30 seconds" would not be.
     */
    @Test
    void what_a_setting_renders_is_what_it_accepts() {
        for (Setting<?> setting : fixture.store().declared()) {
            String rendered = setting.render();
            if (rendered.isBlank()) {
                // Empty by default, and a blank write is refused by design.
                continue;
            }
            fixture.store().set(setting.key(), rendered);
            assertThat(fixture.store().require(setting.key()).render())
                    .as("round-trip of %s", setting.key())
                    .isEqualTo(rendered);
        }
    }
}
