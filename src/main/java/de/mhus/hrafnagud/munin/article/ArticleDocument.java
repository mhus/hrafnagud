package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.api.article.TranslationStatus;
import de.mhus.hrafnagud.api.filter.FilterDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Language;

/**
 * One collected news item, without its body.
 *
 * <p>{@link #dedupKey} is the load-bearing field. A wire report reaches us
 * from every outlet that carries it, and the same outlet re-serves it with
 * different campaign parameters on every poll; without a unique key over a
 * normalised URL, the archive would be mostly duplicates and every
 * downstream query would return the same story a dozen times. The unique
 * index makes that a database guarantee rather than an ingest convention —
 * two workers racing on the same entry cannot both win.
 *
 * <p>{@link #sourceNames} is a list rather than a single reference for the
 * same reason: once articles are deduplicated across sources, "which feed
 * did this come from" has more than one answer, and collapsing it to the
 * first would throw away exactly the information that makes the dedup
 * measurable.
 */
@Document(collection = "articles")
@CompoundIndexes({
        // Identity. Unique, so concurrent ingest of the same entry resolves
        // at the database.
        @CompoundIndex(name = "dedup_idx", def = "{ 'dedupKey': 1 }", unique = true),
        // Default ordering, and the window every consumer asks for.
        @CompoundIndex(name = "seen_idx", def = "{ 'firstSeenAt': -1 }"),
        @CompoundIndex(name = "source_seen_idx", def = "{ 'sourceNames': 1, 'firstSeenAt': -1 }"),
        @CompoundIndex(name = "language_seen_idx", def = "{ 'language': 1, 'firstSeenAt': -1 }"),
        @CompoundIndex(name = "category_seen_idx", def = "{ 'categories': 1, 'firstSeenAt': -1 }"),
        // Containment queries: one index answers "from Asia" and "from
        // Singapore" alike, because the path holds every level.
        @CompoundIndex(name = "origin_place_seen_idx",
                def = "{ 'originPlaceIds': 1, 'firstSeenAt': -1 }"),
        // Same trick as places: the stored path holds every containing topic,
        // so "about sport" and "about cricket" are one index apart.
        @CompoundIndex(name = "topic_seen_idx",
                def = "{ 'topicIds': 1, 'firstSeenAt': -1 }"),
        // Feed ordering. Distinct from the seen_* family above because it
        // answers a different question: those order by when this archive
        // learned of an article, these by when it was published, which is
        // the only key a reader can merge several sources on. The id is
        // part of the key so a cursor can step through a batch that shares
        // a timestamp — see ArticleCursor.
        @CompoundIndex(name = "published_idx", def = "{ 'publishedAt': -1, '_id': -1 }"),
        @CompoundIndex(name = "source_published_idx",
                def = "{ 'sourceNames': 1, 'publishedAt': -1, '_id': -1 }"),
        @CompoundIndex(name = "language_published_idx",
                def = "{ 'language': 1, 'publishedAt': -1, '_id': -1 }"),
        // The content worker's claim query. Partial-filtered to PENDING so
        // the index stays proportional to the backlog rather than to the
        // archive — the difference between thousands and tens of millions.
        @CompoundIndex(name = "content_queue_idx",
                def = "{ 'contentNextAttemptAt': 1 }",
                partialFilter = "{ 'contentStatus': 'PENDING' }"),
        // Near-duplicate detection: same story, different URL.
        @CompoundIndex(name = "content_hash_idx", def = "{ 'contentHash': 1 }"),
        // The translation worker's claim query: newest first, because a news
        // archive that falls behind should be current rather than complete
        // (specs/translation.md §5.2). Partial-filtered to PENDING so the
        // index tracks the backlog rather than the whole archive — the same
        // reason the content queue is.
        //
        // The claim also filters on translationNextAttemptAt, which this index
        // does not cover: with a range predicate on one field and a sort on
        // another, one of the two is served and the other is filtered, and
        // sorting is the expensive half to get wrong. Walking newest-first and
        // skipping the few that are leased or backed off is cheap, because in
        // a healthy queue nearly every PENDING article is due.
        //
        // The status leads the key although the partial filter already pins it
        // to PENDING, and that redundancy is the point: `{ firstSeenAt: -1 }`
        // alone is `seen_idx`, and Mongo rejects a second index with the same
        // key pattern under a different name — error 85, IndexOptionsConflict,
        // whatever the options differ by. It refuses at index *creation*, so
        // the failure is a boot failure on any database that already has
        // seen_idx, which is every existing installation. Leading with the
        // equality field also happens to be the textbook shape for
        // equality-then-sort.
        //
        // The name stays what it was when this pattern went out, and that is
        // the subtle half of the renaming rule: a name follows a *changing*
        // pattern, in the commit that changes it. Renaming afterwards, once
        // every database already holds this pattern under this name, is the
        // mirror-image failure — same keys, different name, error 85 — and it
        // fails on exactly the databases the rename was meant to protect. The
        // older `translation_queue_idx { translationNextAttemptAt: 1 }` is
        // retired and listed in MongoIndexCompatibilityTest.
        @CompoundIndex(name = "translation_lifo_idx",
                def = "{ 'translationStatus': 1, 'firstSeenAt': -1 }",
                partialFilter = "{ 'translationStatus': 'PENDING' }"),
        // Re-evaluating the filter rules walks the archive oldest-examined
        // first, using policyAt as its progress marker so a capped run
        // continues instead of chewing the same head again. Not a hot path —
        // it runs when somebody presses the button — but without an index the
        // walk is a collection scan per batch.
        @CompoundIndex(name = "policy_idx", def = "{ 'policyAt': 1 }"),
        // The `accepted` facet on a timeline. Without it the filter is applied
        // to documents the published index already fetched, which is cheap only
        // while the facet removes a minority — and the interesting rule sets are
        // exactly the ones where it removes most of the archive. The sort keys
        // follow the policy so an $in over two equality points can still walk
        // the index in order.
        @CompoundIndex(name = "translation_policy_published_idx",
                def = "{ 'translationPolicy': 1, 'publishedAt': -1, '_id': -1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDocument {

    @Id
    private @Nullable String id;

    /**
     * SHA-256 of the normalised URL. Unique across the collection — this is
     * the article's identity.
     */
    private String dedupKey = "";

    /** Normalised article URL. */
    private String url = "";

    /** URL exactly as the feed delivered it, kept for provenance. */
    private @Nullable String originalUrl;

    /**
     * SHA-256 over the normalised title and teaser. Not unique: it is the
     * handle for finding the same story republished under a different URL,
     * which is a clustering problem rather than an identity one.
     */
    private String contentHash = "";

    @TextIndexed(weight = 10)
    private String title = "";

    @TextIndexed(weight = 3)
    private @Nullable String summary;

    /**
     * The translated title, mirrored here from the newest
     * {@code TRANSLATION} enrichment so that it can be searched.
     *
     * <p>A derived read model, not a second record. The enrichment stays the
     * append-only truth — this is a copy that exists because MongoDB allows
     * one text index per collection and it has to live on the document being
     * searched. Written in exactly one place, when a translation is recorded.
     *
     * <p>Why at all: with a pivot language the reader is shown the
     * translation, so searching only the original means a German query finds
     * nothing in a German-facing archive ({@code tariffs} hits,
     * {@code Zoelle} does not). Both are indexed, at the same weights as
     * their originals, so a translated title ranks like a title.
     */
    @TextIndexed(weight = 10)
    private @Nullable String pivotTitle;

    /** The translated teaser. See {@link #pivotTitle}. */
    @TextIndexed(weight = 3)
    private @Nullable String pivotSummary;

    private @Nullable String author;

    private @Nullable String imageUrl;

    /** Feed-level entry identifier, kept for debugging odd feeds. */
    private @Nullable String guid;

    // ─── Classification ───

    private @Nullable String language;

    /**
     * The stemmer MongoDB may use for this document's text index — <b>not</b>
     * a second language field.
     *
     * <p>A text index picks its stemmer per document from an override field,
     * and that field defaults to the one called {@code language}. Ours holds
     * a BCP-47 subtag, of which MongoDB accepts fifteen and <em>rejects the
     * write</em> for every other. That made a Japanese, Chinese, Korean,
     * Polish, Czech, Arabic, Ukrainian or Greek article unstorable, and — with
     * no per-article catch in the ingest loop — killed the whole poll of any
     * feed carrying one.
     *
     * <p>{@code @Language} points the override here instead, so
     * {@link #language} stays the honest record of what the article is in
     * while this one stays inside what MongoDB will accept. See
     * {@link TextIndexLanguage}.
     */
    @Language
    private String textLanguage = TextIndexLanguage.NONE;

    private LanguageSource languageSource = LanguageSource.UNKNOWN;

    /** Feed and source categories, verbatim and un-normalised. */
    @Builder.Default
    private List<String> categories = new ArrayList<>();

    /**
     * Normalised topics, with their containing topics — the IPTC Media Topics
     * behind {@link #categories}, outermost first.
     *
     * <p>A <b>derived read model</b>: the mapping table is the record of what a
     * category was decided to mean, and this is the part an index can use. So
     * an article keeps the topics it was written with until something
     * backfills it — a mapping that learns later does not rewrite history by
     * itself, which is worth knowing before assuming otherwise.
     *
     * <p>Empty for an article whose categories are all unresolved, and for one
     * with no categories at all. Both are ordinary.
     */
    @Builder.Default
    private List<String> topicIds = new ArrayList<>();

    // ─── Origin ───

    /**
     * Country of the publisher this article first arrived through, denormalised
     * from the source.
     *
     * <p><b>Origin, never subject.</b> Deutsche Welle sits in Germany and
     * writes in English about the world; a Singapore bureau files about
     * Ukraine. This says who published, and the article's own places — what it
     * is <em>about</em> — are a different field that a different step fills.
     * Merging the two produces a filter that is wrong exactly where it matters
     * and wrong invisibly, because the value looks plausible. See
     * specs/geo.md §1.
     *
     * <p>Frequently null: a country reaches a source only from a hand-set list
     * default.
     */
    private @Nullable String originCountry;

    /**
     * {@link #originCountry} and everything containing it, outermost first —
     * {@code [m49:001, m49:142, m49:035, iso:SG]}.
     *
     * <p>Materialised so that "everything from Asian publishers" is an equality
     * match on a multikey index instead of a hierarchy walk MongoDB cannot do.
     * Empty when the country is unknown or not in the table; an unknown code
     * must not silently become "the world".
     */
    @Builder.Default
    private List<String> originPlaceIds = new ArrayList<>();

    // ─── Provenance ───

    /** Every source that has delivered this article. */
    @Builder.Default
    private List<String> sourceNames = new ArrayList<>();

    /** The source that delivered it first. Never rewritten. */
    private String firstSourceName = "";

    // ─── Time ───

    /** As claimed by the feed; {@code null} when absent or implausible. */
    private @Nullable Instant publishedAt;

    /** When we first saw it. Authoritative for ordering. */
    private Instant firstSeenAt = Instant.EPOCH;

    /**
     * When the most recent source was added to {@link #sourceNames} — that
     * is, the last time a feed delivered this article which had not
     * delivered it before. Equal to {@link #firstSeenAt} for an article only
     * one source ever carried.
     *
     * <p>Named for exactly what it is rather than "last seen", because the
     * ingest path deliberately does not write on a repeat delivery from a
     * source that already has the article. Those are the overwhelming
     * majority of ingest events — a feed re-serves its whole window on every
     * poll — and touching a document to record "still there" would make the
     * archive's dominant write load a field nobody reads.
     */
    private Instant lastSourceAddedAt = Instant.EPOCH;

    // ─── Body ───

    private ContentStatus contentStatus = ContentStatus.PENDING;

    /** {@code ArticleContentDocument.id}, set once the body is stored. */
    private @Nullable String contentId;

    private @Nullable Instant contentFetchedAt;

    /**
     * When the content worker may next try. Doubles as the claim lease, the
     * same way {@code nextFetchAt} does for sources.
     */
    private @Nullable Instant contentNextAttemptAt;

    private int contentAttempts;

    private int contentWordCount;

    private @Nullable String contentError;

    // ─── Translation ───

    /**
     * Whether this article has been rendered into the pivot language.
     *
     * <p>Only the state lives here; the translation itself is an
     * {@code EnrichmentDocument}. A result that can be produced again by
     * a better model does not belong on the article — the article is what
     * was collected, not what has since been computed from it.
     */
    private TranslationStatus translationStatus = TranslationStatus.PENDING;

    /**
     * When the translation worker may next try. Doubles as the claim
     * lease, exactly as {@link #contentNextAttemptAt} does for bodies.
     */
    private @Nullable Instant translationNextAttemptAt;

    private int translationAttempts;

    private @Nullable String translationError;

    // ─── Filter decisions ───
    //
    // Why an article is in, or out of, each of the two expensive queues. The
    // status field remains the queue; these say how it got its value, which is
    // what makes "why is this not translated" answerable from the article
    // instead of by re-deriving it against rules that may have changed since.
    //
    // Each pair distinguishes two SKIPPED that mean different things: filtered
    // out (DENY) and nothing to do — already in the pivot language, or body
    // fetching switched off — which is ACCEPT. Only the first is undone by
    // changing a rule, which is what re-evaluation relies on. See
    // specs/filter.md §6.

    private FilterDecision contentPolicy = FilterDecision.ACCEPT;

    /** Name of the deciding rule; null when the default applied. */
    private @Nullable String contentPolicyRule;

    private FilterDecision translationPolicy = FilterDecision.ACCEPT;

    private @Nullable String translationPolicyRule;

    /**
     * When the rules were last applied to this article — ingest, or a
     * re-evaluation run.
     */
    private @Nullable Instant policyAt;

    @Version
    private @Nullable Long version;
}
