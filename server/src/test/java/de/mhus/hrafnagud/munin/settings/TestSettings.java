package de.mhus.hrafnagud.munin.settings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Settings for a unit test: the configured defaults, plus whatever the test
 * wants to override.
 *
 * <p>The repository is a mock, so nothing here touches a database. Two ways in,
 * and which one to use follows from what the test is about:
 *
 * <ul>
 *   <li>{@link #of(MuninProperties)} when the test is about a <b>configured</b>
 *       value — build a {@code MuninProperties}, set what you need, pass it.
 *       That is the same layer {@code application.yml} feeds.</li>
 *   <li>{@link #with(Map)} when the test is about an <b>override</b>, which is
 *       the layer the operator writes. The values are text, exactly as they
 *       arrive from the API.</li>
 * </ul>
 */
public final class TestSettings {

    private TestSettings() {
    }

    /**
     * The store and the settings built on it, for a test that also writes.
     *
     * @param store    the write path — {@code set} and {@code reset}
     * @param settings the handles the production code holds
     */
    public record Fixture(SettingsService store, MuninSettings settings) {
    }

    /** Nothing overridden: every setting reports its code default. */
    public static MuninSettings defaults() {
        return of(new MuninProperties());
    }

    /** Nothing overridden, defaults taken from the given properties. */
    public static MuninSettings of(MuninProperties properties) {
        return build(properties, Map.of());
    }

    /** Code defaults with these overrides stored on top. */
    public static MuninSettings with(Map<String, String> overrides) {
        return build(new MuninProperties(), overrides);
    }

    /** Code defaults with one override stored on top. */
    public static MuninSettings with(String key, String value) {
        return with(Map.of(key, value));
    }

    public static MuninSettings build(MuninProperties properties, Map<String, String> overrides) {
        return fixture(properties, overrides).settings();
    }

    public static Fixture fixture(MuninProperties properties, Map<String, String> overrides) {
        SettingRepository repository = stubRepository(overrides);
        SettingsService store = new SettingsService(repository);
        MuninSettings settings = new MuninSettings(store, properties);
        // After the declarations, so the store's own unknown-key check sees the
        // same picture it sees in a running service.
        store.load();
        return new Fixture(store, settings);
    }

    /**
     * A repository backed by a map, so a test can also write through
     * {@link SettingsService#set} and see the effect.
     *
     * <p>Stubbed leniently: most tests only ever read, and under strict stubs
     * the write-path stubs would then fail the test they are not used by.
     */
    private static SettingRepository stubRepository(Map<String, String> initial) {
        Map<String, SettingDocument> rows = new LinkedHashMap<>();
        initial.forEach((key, value) -> rows.put(key,
                SettingDocument.builder().key(key).value(value).build()));

        SettingRepository repository = mock(SettingRepository.class);
        lenient().when(repository.findAll())
                .thenAnswer(invocation -> new ArrayList<>(rows.values()));
        lenient().when(repository.findByKey(any())).thenAnswer(invocation ->
                Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        lenient().when(repository.save(any(SettingDocument.class))).thenAnswer(invocation -> {
            SettingDocument doc = invocation.getArgument(0);
            rows.put(doc.getKey(), doc);
            return doc;
        });
        lenient().doAnswer(invocation -> rows.remove(invocation.<String>getArgument(0)))
                .when(repository).deleteByKey(any());
        return repository;
    }
}
