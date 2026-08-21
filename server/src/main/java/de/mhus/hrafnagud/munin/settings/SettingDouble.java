package de.mhus.hrafnagud.munin.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/** A fractional number — confidence thresholds. */
public final class SettingDouble extends Setting<Double> {

    SettingDouble(SettingsService store, String key, Supplier<Double> defaultValue,
            String description) {
        super(store, key, SettingType.DOUBLE, defaultValue, description);
    }

    @Override
    protected Double parse(String raw) {
        double parsed = Double.parseDouble(raw);
        if (!Double.isFinite(parsed)) {
            // NaN and the infinities parse happily and then poison every
            // comparison they reach, silently and without an exception.
            throw new IllegalArgumentException("not a finite number: " + raw);
        }
        return parsed;
    }
}
