package de.mhus.hrafnagud.munin.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/** A switch. {@code true}/{@code false}, and nothing else counts as either. */
public final class SettingBoolean extends Setting<Boolean> {

    SettingBoolean(SettingsService store, String key, Supplier<Boolean> defaultValue,
            String description) {
        super(store, key, SettingType.BOOLEAN, defaultValue, description);
    }

    @Override
    protected Boolean parse(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        // Not Boolean.parseBoolean: that reads "yes", "1" and "off" all as
        // false, so a typo would switch a subsystem off silently.
        throw new IllegalArgumentException("not a boolean: " + raw);
    }
}
