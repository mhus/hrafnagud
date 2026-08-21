package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/**
 * A fraction between zero and one — confidence thresholds and a model
 * temperature.
 *
 * <p>Bounded rather than merely finite, because that is what both consumers
 * mean by the number: a confidence is compared against a score on that scale,
 * and a temperature outside it is rejected by the provider one call later.
 * Zero is allowed and useful at both ends — accept every match, sample
 * deterministically.
 */
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
        if (parsed < 0.0 || parsed > 1.0) {
            throw new IllegalArgumentException("not a fraction between 0 and 1: " + raw);
        }
        return parsed;
    }
}
