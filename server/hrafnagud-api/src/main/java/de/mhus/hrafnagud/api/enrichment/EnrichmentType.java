package de.mhus.hrafnagud.api.enrichment;

/**
 * What kind of processing produced an enrichment.
 *
 * <p>One value today. The type exists from the start because it is what
 * makes the collection extensible: a later stage — keywords, sentiment,
 * an embedding — is a new value and a new worker, not a change to the
 * article schema or to anything already stored.
 */
public enum EnrichmentType {

    /** Title and teaser rendered in the pivot language. */
    TRANSLATION
}
