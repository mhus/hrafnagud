package de.mhus.hrafnagud.api.filter;

/**
 * What a rule looks at.
 *
 * <p>Four of these read a list rather than a single value, and there a rule
 * matches when <b>any</b> element matches. For {@link #SOURCE} that is a
 * decision rather than a convenience: an article deduplicated across two feeds
 * has both of them, and matching only the first seen would make the outcome
 * depend on which feed happened to be polled first.
 */
public enum FilterRuleType {

    /** The article's canonical URL. */
    URL(false),

    /**
     * The host of that URL.
     *
     * <p>Its own type because {@code CONTAINS youtube.com} against a full URL
     * also matches a foreign URL that merely mentions YouTube in a query
     * parameter — and "no YouTube" is the first rule anybody writes. With
     * {@link FilterMatchType#SUFFIX} this nests the way domains nest.
     */
    HOST(false),

    /** Any source the article arrived through, by name. */
    SOURCE(true),

    /** The detected article language, as a two-letter code. */
    LANGUAGE(false),

    /**
     * The publisher's place path — {@code m49:142} matches a Singaporean
     * source, because the ancestors are materialised.
     *
     * <p>This is <b>origin</b>, where the publisher sits, and not what the
     * article is about. A rule written in the belief that it selects articles
     * <em>about</em> a region will be wrong in a way that looks like it works;
     * see specs/geo.md §3.2.
     */
    REGION(true),

    /**
     * The raw category strings the publisher wrote.
     *
     * <p>Unnormalised and in whatever language the publisher uses — and
     * therefore the category type that works at ingest, when {@link #TOPIC} is
     * usually still empty.
     */
    CATEGORY(true),

    /**
     * The normalised Media Topics, ancestors included — {@code medtop:15000000}
     * matches an article tagged <i>Cricket</i>.
     *
     * <p>Clean and language-independent, and mostly <b>not yet resolved</b>
     * when a new article is filtered: stage two of category normalisation is
     * asynchronous and off by default. Rules of this type earn their keep
     * through re-evaluation, not at ingest — specs/filter.md §3.1.
     */
    TOPIC(true),

    /**
     * The source's fetch profile.
     *
     * <p>This is the <b>polling cadence class</b>, not a genre. That its values
     * are {@code news} and {@code blog} today is an artefact of how the bundled
     * catalogues were split; when a real publication kind exists it becomes a
     * source field with its own rule type, and rules written against this one
     * keep meaning what they said.
     */
    PROFILE(false);

    private final boolean multiValued;

    FilterRuleType(boolean multiValued) {
        this.multiValued = multiValued;
    }

    /** Whether the article offers several values for this type. */
    public boolean multiValued() {
        return multiValued;
    }
}
