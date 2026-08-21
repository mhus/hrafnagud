package de.mhus.hrafnagud.munin.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/** Free text — a user agent, a language subtag. */
public final class SettingString extends Setting<String> {

    SettingString(SettingsService store, String key, Supplier<String> defaultValue,
            String description) {
        super(store, key, SettingType.STRING, defaultValue, description);
    }

    @Override
    protected String parse(String raw) {
        return raw;
    }
}
