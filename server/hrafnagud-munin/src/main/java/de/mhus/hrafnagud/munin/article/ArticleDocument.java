package de.mhus.hrafnagud.munin.article;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.article.LanguageSource;
import de.mhus.hrafnagud.api.article.TranslationStatus;
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
        // The translation worker's claim query. Partial-filtered to
        // PENDING so the index tracks the backlog rather than the whole
        // archive — the same reason the content queue is.
        @CompoundIndex(name = "translation_queue_idx",
                def = "{ 'translationNextAttemptAt': 1 }",
                partialFilter = "{ 'translationStatus': 'PENDING' }")
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

    private @Nullable String author;

    private @Nullable String imageUrl;

    /** Feed-level entry identifier, kept for debugging odd feeds. */
    private @Nullable String guid;

    // ─── Classification ───

    private @Nullable String language;

    private LanguageSource languageSource = LanguageSource.UNKNOWN;

    /** Feed and source categories, verbatim and un-normalised. */
    @Builder.Default
    private List<String> categories = new ArrayList<>();

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

    @Version
    private @Nullable Long version;
}
