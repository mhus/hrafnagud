package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/** A whole number that may exceed an {@code int} — byte caps, mostly. */
public final class SettingLong extends Setting<Long> {

    SettingLong(SettingsService store, String key, Supplier<Long> defaultValue,
            String description) {
        super(store, key, SettingType.LONG, defaultValue, description);
    }

    @Override
    protected Long parse(String raw) {
        return Long.valueOf(raw);
    }
}
