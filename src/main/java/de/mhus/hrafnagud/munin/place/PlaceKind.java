package de.mhus.hrafnagud.munin.place;

/**
 * Rungs of the containment ladder that ships with the application.
 *
 * <p>Stops at the country deliberately. Everything below — states, cities —
 * needs a gazetteer, which is a download and a licence rather than a 20 KB
 * resource, and nothing yet asks questions at that resolution.
 */
public enum PlaceKind {

    /** The root, {@code m49:001}. Every path starts here. */
    WORLD,

    /** UN M.49 region: Africa, Americas, Asia, Europe, Oceania. */
    REGION,

    /** UN M.49 sub-region: South-Eastern Asia, Western Europe, … */
    SUBREGION,

    /**
     * UN M.49 intermediate region — Caribbean, Channel Islands and five more.
     * Present for only part of the world, which is why a country's parent is
     * whichever of the three levels above it actually exists.
     */
    INTERMEDIATE_REGION,

    /** ISO 3166-1 alpha-2 country or area. */
    COUNTRY
}
