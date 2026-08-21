package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/** A whole number that fits an {@code int} — batch sizes, attempt budgets, thresholds. */
public final class SettingInt extends Setting<Integer> {

    SettingInt(SettingsService store, String key, Supplier<Integer> defaultValue,
            String description) {
        super(store, key, SettingType.INT, defaultValue, description);
    }

    @Override
    protected Integer parse(String raw) {
        return Integer.valueOf(raw);
    }
}
