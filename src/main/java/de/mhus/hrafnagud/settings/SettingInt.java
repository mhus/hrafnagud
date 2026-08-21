package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/**
 * A whole number that fits an {@code int} — batch sizes, attempt budgets,
 * thresholds.
 *
 * <p><b>Positive, and that is a range and not a type check.</b> Every one of
 * these is a count of something to do, and zero is a working service that does
 * nothing while reporting that it is on: {@code munin.feed.batchSize: 0} makes
 * the claim loop run zero times and {@code WorkerSwitch} still says "on", and
 * {@code hugin.translation.maxSourceChars: 0} truncates every title to nothing
 * and then charges each article its whole attempt budget for having none. Both
 * are reachable through the API, which is exactly what {@code validate} is for:
 * a value a worker cannot use is refused where it is written.
 */
public final class SettingInt extends Setting<Integer> {

    SettingInt(SettingsService store, String key, Supplier<Integer> defaultValue,
            String description) {
        super(store, key, SettingType.INT, defaultValue, description);
    }

    @Override
    protected Integer parse(String raw) {
        int parsed = Integer.parseInt(raw);
        if (parsed <= 0) {
            throw new IllegalArgumentException("not a positive number: " + raw);
        }
        return parsed;
    }
}
