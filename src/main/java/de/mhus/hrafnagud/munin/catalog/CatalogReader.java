package de.mhus.hrafnagud.munin.catalog;

/**
 * Resolves a catalogue into the source lists it offers.
 *
 * <p>One implementation per <b>publication shape</b>, not per publisher.
 * {@code opml-directory} handles every directory that follows the OPML 2.0
 * spec; {@code github-opml} handles every repository that keeps loose OPML
 * files in a directory. A well-known collection is then a row in the
 * database, not a class — which is the difference between adding a catalogue
 * and shipping a release.
 *
 * <p>Implementations must be safe to call from several threads and should
 * fail by throwing {@link CatalogReadException}: the caller records the
 * failure against the catalogue, backs the retry off and keeps the lists it
 * already had. Returning an empty list instead would be read as "the
 * directory dropped everything", which is how a network blip disables a
 * registry.
 */
public interface CatalogReader {

    /** Id of this reader, as stored in {@code SourceCatalogDocument.type}. */
    String type();

    /** Human-readable name of the publication shape, for diagnostics and the UI. */
    String displayName();

    /**
     * Enumerate the lists.
     *
     * <p>Enumerating means URLs and labels. A reader that downloads and
     * parses every list it found has moved the layer below into this one and
     * will turn a daily refresh into a crawl.
     */
    CatalogReadResult read(SourceCatalogDocument catalog);
}
