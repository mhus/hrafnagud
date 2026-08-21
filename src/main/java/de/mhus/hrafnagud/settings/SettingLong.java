package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;

/**
 * A whole number that may exceed an {@code int} — byte caps, mostly.
 *
 * <p>Positive, for the same reason as {@link SettingInt}: a cap of zero bytes is
 * not a strict limit, it is a fetcher that rejects everything while looking
 * configured.
 */
public final class SettingLong extends Setting<Long> {

    SettingLong(SettingsService store, String key, Supplier<Long> defaultValue,
            String description) {
        super(store, key, SettingType.LONG, defaultValue, description);
    }

    @Override
    protected Long parse(String raw) {
        long parsed = Long.parseLong(raw);
        if (parsed <= 0) {
            throw new IllegalArgumentException("not a positive number: " + raw);
        }
        return parsed;
    }
}
