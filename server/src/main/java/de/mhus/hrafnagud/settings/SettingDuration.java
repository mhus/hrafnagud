package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * A span of time, written the way {@code application.yml} writes it:
 * {@code PT30S}, {@code PT15M}, {@code PT24H}.
 *
 * <p>ISO-8601 rather than Spring's relaxed {@code 30s} form, because this text
 * is read back by {@link Duration#parse} rather than by the property binder,
 * and one syntax that round-trips beats two that nearly agree.
 */
public final class SettingDuration extends Setting<Duration> {

    SettingDuration(SettingsService store, String key, Supplier<Duration> defaultValue,
            String description) {
        super(store, key, SettingType.DURATION, defaultValue, description);
    }

    @Override
    protected Duration parse(String raw) {
        Duration parsed = Duration.parse(raw);
        if (parsed.isNegative()) {
            throw new IllegalArgumentException("not a positive duration: " + raw);
        }
        return parsed;
    }
}
