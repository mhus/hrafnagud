package de.mhus.hrafnagud.munin.place;

/**
 * A loaded {@link PlaceRegistry} for tests outside this package.
 *
 * <p>{@code load()} is the container's {@code @PostConstruct} hook and stays
 * package-private — widening it so a test in another package can call it would
 * put a lifecycle method into the public API for no runtime reason. This
 * fixture lives here instead, where the visibility already allows it.
 */
public final class TestPlaces {

    private TestPlaces() {
        /* static only */
    }

    public static PlaceRegistry loaded() {
        PlaceRegistry registry = new PlaceRegistry();
        registry.load();
        return registry;
    }
}
