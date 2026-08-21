package de.mhus.hrafnagud.munin.settings;

import de.mhus.hrafnagud.api.setting.SettingType;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A handle on one setting: ask it for {@link #value()} and get whatever is in
 * force right now.
 *
 * <p>A handle rather than a value, because a value read once is a value from
 * whenever it was read. Consumers here are long-lived singletons — a tick, a
 * service, a fetcher — and every one of them used to take its numbers in the
 * constructor, which is exactly why changing them meant a restart. Holding the
 * handle instead moves the read to the moment it matters and needs no listener,
 * no refresh scope and no proxy: the call site is one word longer and correct
 * for the rest of the process's life.
 *
 * <p>Resolution is <b>override, else configured default</b>. The default is
 * read from {@link de.mhus.hrafnagud.munin.config.MuninProperties} rather than
 * copied out of it, so {@code application.yml} and the {@code HRAFNAGUD_*}
 * environment stay the layer underneath and deleting the override is the way
 * back to them. Nothing is captured at start-up, which means there is no
 * question of when a default was read.
 *
 * <p>A blank override counts as no override. That makes one gesture — clear the
 * field — mean the same thing everywhere, and it costs the ability to store an
 * explicitly empty string in a setting whose default is not empty. The one
 * place that would matter, {@code munin.translation.pivotLanguage}, has an
 * empty default already, so its "off" is reachable by deleting the override.
 */
public abstract class Setting<T> {

    private static final Logger log = LoggerFactory.getLogger(Setting.class);

    private final SettingsService store;
    private final String key;
    private final SettingType type;
    private final Supplier<T> defaultValue;
    private final String description;

    /**
     * Last resolution together with the store generation it was read from, in
     * one field so a reader cannot see a value from one generation labelled
     * with another. Two threads racing on a stale generation both resolve and
     * both get the same answer, which is why this needs no lock.
     */
    private volatile @Nullable Resolved<T> resolved;

    private record Resolved<V>(long generation, V value) {}

    protected Setting(SettingsService store, String key, SettingType type,
            Supplier<T> defaultValue, String description) {
        this.store = store;
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    /** The value in force. */
    public final T value() {
        long generation = store.generation();
        Resolved<T> current = resolved;
        if (current != null && current.generation() == generation) {
            return current.value();
        }
        T fresh = resolve();
        resolved = new Resolved<>(generation, fresh);
        return fresh;
    }

    private T resolve() {
        String raw = store.raw(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue.get();
        }
        try {
            return parse(raw.trim());
        } catch (RuntimeException e) {
            // Writes through the API are parsed before they are stored, so this
            // is an override that was edited straight in the database. Falling
            // back to the default and saying so beats a worker dying on it.
            log.warn("Setting {} holds '{}', which is not a {} — using the configured default {}",
                    key, raw, type, defaultValue.get());
            return defaultValue.get();
        }
    }

    /** Reads the text form. Throws whatever the parse throws — see {@link #validate}. */
    protected abstract T parse(String raw);

    /**
     * Writes the text form. Must produce something {@link #parse} accepts —
     * that round-trip is what lets the console show a value and hand the same
     * string back on save.
     */
    protected String format(T value) {
        return String.valueOf(value);
    }

    /**
     * Checks that {@code raw} is a value of this type, throwing if it is not.
     * The write path calls this so that a bad value is refused at the API
     * instead of being found by a worker.
     */
    public final void validate(String raw) {
        parse(raw.trim());
    }

    public final String key() {
        return key;
    }

    public final SettingType type() {
        return type;
    }

    public final T defaultValue() {
        return defaultValue.get();
    }

    public final String description() {
        return description;
    }

    /** {@code true} when an override is stored, whatever it says. */
    public final boolean overridden() {
        return store.raw(key) != null;
    }

    /** The value in force, as text — what the API and the console show. */
    public final String render() {
        return format(value());
    }

    /** The configured default, as text. */
    public final String renderDefault() {
        return format(defaultValue.get());
    }

    @Override
    public final String toString() {
        return key + "=" + render();
    }
}
