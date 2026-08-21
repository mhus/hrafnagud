package de.mhus.hrafnagud.api.category;

/**
 * How far a raw category has got towards a normalised topic.
 *
 * <p>Doubles as the work queue: the pending values are what the resolution
 * tick claims, indexed partially so the index stays proportional to the
 * backlog rather than to the vocabulary.
 *
 * <p><b>Two of these are terminal and are not topics at all.</b> That is the
 * point rather than an afterthought: a publisher's category field carries
 * topics, places, people, products and section names in one list, and without
 * somewhere to put "this is not a topic" the resolver is asked about a
 * person's name every week and pays each time to learn the same nothing.
 */
public enum CategoryMappingStatus {

    /** Seen at ingest, nothing tried yet. */
    NEW,

    /**
     * String matching found something, but not well enough to act on — a
     * single word that appears in some label, say. Waiting for stage two.
     */
    GUESSED,

    /** Resolved to a topic, by matching or by a model. */
    RESOLVED,

    /** A human agreed. Never revisited, by anything. */
    CONFIRMED,

    /** A format, a person, a product, noise. Terminal. */
    NOT_A_TOPIC,

    /**
     * A place, not a topic. Terminal here — and evidence about what an article
     * is <em>about</em>, which is what specs/geo.md leaves unbuilt. The
     * information is kept rather than discarded.
     */
    IS_PLACE,

    /** Stage two could not decide within its attempts. Waits for a person. */
    FAILED;

    /** Whether the resolution tick should still look at this. */
    public boolean pending() {
        return this == NEW || this == GUESSED;
    }

    /** Whether this yields a topic for the article. */
    public boolean resolved() {
        return this == RESOLVED || this == CONFIRMED;
    }
}
