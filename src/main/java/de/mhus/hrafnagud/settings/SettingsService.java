package de.mhus.hrafnagud.settings;

import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Holds the stored overrides and hands out {@link Setting} handles over them.
 *
 * <p>Two things happen here, and both are deliberately dull. Every setting the
 * code reads is <b>declared</b> once — see {@link Settings}, which is the
 * list — and the declaration is what makes the key writable: a {@code PUT} to
 * an undeclared key is refused rather than stored somewhere nothing reads it.
 * And the whole collection is held as one immutable snapshot, replaced by a
 * fresh one when something changes, so a worker mid-round never sees half of an
 * edit.
 *
 * <p>The snapshot carries a <b>generation</b> counter that only moves when the
 * values actually differ. Handles use it to avoid re-parsing on every read, and
 * anything that derives a more expensive object from settings — a fetch
 * schedule, say — can hold on to it and rebuild only when it changes.
 *
 * <p>Reloading is a write-through plus a poll: the console's own edits are
 * visible immediately, and the poll is what catches an edit made straight in
 * the database or by a second instance. The collection holds one small document
 * per changed value, so reading all of it is cheaper than being clever about it.
 */
@Service
@Slf4j
public class SettingsService {

    private final SettingRepository repository;

    /** Declared settings in declaration order, which is the order the API lists them. */
    private final Map<String, Setting<?>> declared = new LinkedHashMap<>();

    /** Keys already reported as undeclared, so the warning is news and not noise. */
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * The stored overrides at one point in time.
     *
     * @param values    key to raw text
     * @param updatedAt key to when it was last written
     */
    private record Snapshot(Map<String, String> values, Map<String, Instant> updatedAt,
            long generation) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), 0L);
        }
    }

    public SettingsService(SettingRepository repository) {
        this.repository = repository;
    }

    // ──────────────────── Declaration ────────────────────
    //
    // The default is a supplier, not a value: it reads MuninProperties, which
    // is the configuration layer underneath. Nothing is copied out of it, so a
    // setting has one default and it is wherever the properties say it is.

    public SettingBoolean bool(String key, Supplier<Boolean> defaultValue, String description) {
        return declare(new SettingBoolean(this, key, defaultValue, description));
    }

    public SettingInt integer(String key, Supplier<Integer> defaultValue, String description) {
        return declare(new SettingInt(this, key, defaultValue, description));
    }

    public SettingLong number(String key, Supplier<Long> defaultValue, String description) {
        return declare(new SettingLong(this, key, defaultValue, description));
    }

    public SettingDouble fraction(String key, Supplier<Double> defaultValue,
            String description) {
        return declare(new SettingDouble(this, key, defaultValue, description));
    }

    public SettingString text(String key, Supplier<String> defaultValue, String description) {
        return declare(new SettingString(this, key, defaultValue, description));
    }

    public SettingDuration duration(String key, Supplier<Duration> defaultValue,
            String description) {
        return declare(new SettingDuration(this, key, defaultValue, description));
    }

    public SettingLanguages languages(String key, Supplier<Set<String>> defaultValue,
            String description) {
        return declare(new SettingLanguages(this, key, defaultValue, description));
    }

    private <S extends Setting<?>> S declare(S setting) {
        Setting<?> clash = declared.putIfAbsent(setting.key(), setting);
        if (clash != null) {
            // A programming error, and a quiet one if it were allowed: two
            // declarations of one key mean two defaults and two descriptions,
            // and which of them the console shows would be an accident.
            throw new IllegalStateException("Setting " + setting.key() + " is declared twice");
        }
        return setting;
    }

    // ──────────────────── Reading ────────────────────

    /** The raw override, or {@code null} when the setting falls through to its default. */
    @Nullable
    String raw(String key) {
        return snapshot.values().get(key);
    }

    /** Changes when the stored values change, and not otherwise. */
    long generation() {
        return snapshot.generation();
    }

    /** Every declared setting, in declaration order. */
    public List<Setting<?>> declared() {
        return List.copyOf(declared.values());
    }

    /** The declared setting of that key. */
    public Setting<?> require(String key) {
        Setting<?> setting = declared.get(key);
        if (setting == null) {
            throw new NotFoundException("Setting", key);
        }
        return setting;
    }

    /** When the override was last written, or empty when there is none. */
    public Optional<Instant> updatedAt(String key) {
        return Optional.ofNullable(snapshot.updatedAt().get(key));
    }

    // ──────────────────── Writing ────────────────────

    /**
     * Stores an override for a declared key, after checking that the text is a
     * value of that key's type.
     *
     * @throws NotFoundException   the key is not declared
     * @throws BadRequestException the value does not parse as the declared type
     */
    public void set(String key, @Nullable String value) {
        Setting<?> setting = require(key);
        if (value == null || value.isBlank()) {
            // Blank is not an override — see Setting's class comment. Saying so
            // beats storing a row that resolves to the default anyway and then
            // reads, in the console, as a change somebody made.
            throw new BadRequestException(
                    "A blank value is not an override; DELETE the setting to return it "
                            + "to its configured default");
        }
        String trimmed = value.trim();
        try {
            setting.validate(trimmed);
        } catch (RuntimeException e) {
            throw new BadRequestException(
                    key + " is a " + setting.type() + ": " + e.getMessage());
        }
        SettingDocument doc = repository.findByKey(key)
                .orElseGet(() -> SettingDocument.builder().key(key).build());
        doc.setValue(trimmed);
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
        log.info("Setting {} set to '{}' (default '{}')", key, trimmed, setting.renderDefault());
        reload();
    }

    /** Removes the override, returning the setting to its configured default. */
    public void reset(String key) {
        Setting<?> setting = require(key);
        if (repository.findByKey(key).isEmpty()) {
            // Idempotent on purpose: "make this the default" has succeeded when
            // there is no override, whether or not this call is what removed it.
            log.debug("Setting {} has no override to remove", key);
            return;
        }
        repository.deleteByKey(key);
        log.info("Setting {} reset to its configured default '{}'", key, setting.renderDefault());
        reload();
    }

    // ──────────────────── Loading ────────────────────

    @PostConstruct
    void load() {
        reload();
        int overrides = snapshot.values().size();
        if (overrides == 0) {
            log.info("Settings: no stored overrides — every value comes from the configuration");
        } else {
            log.info("Settings: {} stored override(s) in force: {}",
                    overrides, snapshot.values().keySet());
        }
    }

    /**
     * Picks up edits this instance did not make. Everything written through
     * {@link #set} and {@link #reset} is already visible; this is for a value
     * changed straight in the database, and for the second instance the
     * deployment is not supposed to have but might.
     */
    @Scheduled(fixedDelayString = "${hrafnagud.settings.refreshInterval:PT30S}",
            initialDelayString = "${hrafnagud.settings.refreshInterval:PT30S}")
    void refresh() {
        reload();
    }

    private void reload() {
        List<SettingDocument> stored;
        try {
            stored = repository.findAll();
        } catch (RuntimeException e) {
            // Without the database there are no overrides to read, and the
            // configured defaults are a working service rather than a broken
            // one. Everything else here needs Mongo anyway, so this states the
            // situation instead of pre-empting it.
            log.warn("Settings could not be read ({}) — keeping the last known values",
                    e.toString());
            return;
        }

        Map<String, String> values = new HashMap<>();
        Map<String, Instant> updatedAt = new HashMap<>();
        for (SettingDocument doc : stored) {
            if (doc.getKey().isBlank()) {
                continue;
            }
            values.put(doc.getKey(), doc.getValue());
            if (doc.getUpdatedAt() != null) {
                updatedAt.put(doc.getKey(), doc.getUpdatedAt());
            }
        }

        Snapshot current = snapshot;
        if (values.equals(current.values())) {
            // Same values: keep the generation so the handles keep their parsed
            // value and nothing derived from settings rebuilds every poll.
            return;
        }
        snapshot = new Snapshot(Map.copyOf(values), Map.copyOf(updatedAt),
                current.generation() + 1);
        log.debug("Settings reloaded: {} override(s), generation {}",
                values.size(), current.generation() + 1);
        warnAboutUnknownKeys(values.keySet());
    }

    /**
     * Reports the stored overrides nothing declares, once the declarations
     * exist.
     *
     * <p>Which is why this hangs off {@code ApplicationReadyEvent} rather than
     * off the load: the first load runs from this bean's own
     * {@code @PostConstruct}, before {@link Settings} has been constructed, so
     * the check there sees an empty declaration list and skips. Afterwards the
     * poll short-circuits on unchanged values and never reaches it either — so
     * an override left behind by a rename, the exact case this exists for, was
     * the one case never reported.
     */
    @EventListener(ApplicationReadyEvent.class)
    void reportUnknownKeys() {
        warnAboutUnknownKeys(snapshot.values().keySet());
    }

    /**
     * An override whose key nothing declares is dead weight — most likely a key
     * that was renamed or removed in a release, and it will never be read
     * again. Naming it beats leaving somebody to wonder why their change has no
     * effect.
     *
     * <p>Once per key, not once per poll: the poll runs every thirty seconds and
     * a stale override survives until somebody deletes it, which would be a
     * warning every thirty seconds for as long as the service runs.
     */
    private void warnAboutUnknownKeys(Iterable<String> keys) {
        for (String key : undeclaredAmong(keys)) {
            if (warnedUnknown.add(key)) {
                log.warn("Stored setting {} is not declared by this build — it is ignored. "
                        + "Delete it, or check the release notes for a rename.", key);
            }
        }
    }

    /**
     * Which of these keys nothing declares, in the order given.
     *
     * <p>Empty while nothing is declared at all, because that is not "every key
     * is unknown" but "the declarations have not been built yet" — the first
     * load runs from this bean's {@code @PostConstruct}, before
     * {@link Settings} exists.
     */
    List<String> undeclaredAmong(Iterable<String> keys) {
        if (declared.isEmpty()) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        for (String key : keys) {
            if (!declared.containsKey(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    /** The stored overrides this build has no declaration for. */
    List<String> undeclaredOverrides() {
        return undeclaredAmong(snapshot.values().keySet());
    }
}
