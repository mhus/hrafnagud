package de.mhus.hrafnagud.api.source;

/**
 * Kind of a news source, which decides the reader implementation used to
 * poll it.
 *
 * <p>One value today. The type exists from the start because the reader
 * lookup is a registry keyed by this enum — adding a scraper or an API
 * client later is a new value plus a new bean, not a refactor of the
 * ingest loop.
 *
 * <ul>
 *   <li>{@link #RSS} — an XML feed. Covers RSS 0.9x/1.0/2.0 <em>and</em>
 *       Atom: both are parsed by the same reader, so splitting them into
 *       two values would buy nothing. A feed's dialect is a property of
 *       the bytes, not of our configuration.</li>
 * </ul>
 */
public enum SourceType {

    /** XML syndication feed (RSS or Atom, any dialect). */
    RSS
}
