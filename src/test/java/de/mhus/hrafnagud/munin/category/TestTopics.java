package de.mhus.hrafnagud.munin.category;

/**
 * A loaded {@link TopicRegistry} for tests outside this package — see
 * {@code TestPlaces} for why the loader itself stays package-private.
 */
public final class TestTopics {

    private TestTopics() {
        /* static only */
    }

    public static TopicRegistry loaded() {
        TopicRegistry registry = new TopicRegistry();
        registry.load();
        return registry;
    }
}
