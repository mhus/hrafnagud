package de.mhus.hrafnagud.munin.image;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One image the archive keeps, or is trying to keep.
 *
 * <h2>The id is derived from the URL</h2>
 * {@code sha256(url)}, hex, not a generated id — see {@link ImageKey}. Two
 * articles referencing one image are one record, an article re-extracted is
 * the same record, and above all: whoever holds an image URL can compute the
 * record's address without a lookup by URL. That is what lets a serving path
 * ask "do we have this?" with a primary-key read.
 *
 * <h2>The bytes live in the document</h2>
 * Not GridFS. GridFS earns its complexity above the 16 MB document limit and
 * for range reads into large files; a news image is neither — the largest in
 * a sample of mainstream outlets was 384 KiB, and {@code munin.image.maxBytes}
 * caps it at 4 MiB. A second storage mechanism with its own buckets, chunk
 * collection and indexes would buy nothing here.
 *
 * <p>The consequence is a rule rather than a caveat: a query that does not
 * need the bytes must project them away, or listing images pulls the archive's
 * whole image volume through the driver. {@link ImageService} does that.
 *
 * <h2>No byte-level deduplication</h2>
 * {@code contentHash} is recorded but not unique, and identical bytes under
 * two URLs are stored twice. Measuring says why: outlets carrying the same
 * agency photo each re-encode their own crop, so byte-identical files are
 * rare between publishers, and the case that does repeat — one publisher
 * reusing a photo, an article re-extracted — is already collapsed by the
 * URL-derived id. Refcounting a shared blob would be real complexity bought
 * for a saving that is not there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "images")
@CompoundIndexes({
        // The queue. Partial on the status, so the index stays proportional
        // to the backlog rather than to the archive — and an equality filter,
        // which is all MongoDB 4.4 accepts (see MongoIndexCompatibilityTest).
        @CompoundIndex(name = "image_queue_idx", def = "{ 'status': 1, 'nextAttemptAt': 1 }",
                partialFilter = "{ 'status': 'PENDING' }"),
        // For the date-partitioned view a mount exposes, and for retention.
        @CompoundIndex(name = "image_seen_idx", def = "{ 'firstSeenAt': -1 }")
})
public class ImageDocument {

    /** {@code sha256(url)} in hex — see {@link ImageKey}. */
    @Id
    private String id = "";

    /** The publisher URL these bytes came from, as extraction saw it. */
    private String url = "";

    private ImageStatus status = ImageStatus.PENDING;

    /**
     * The bytes, once fetched.
     *
     * <p>Null while pending and after a failure, which is the same thing the
     * status says — kept as one field rather than two so there is no state in
     * which they disagree.
     */
    private byte @Nullable [] data;

    /** Length of {@link #data}, so a listing does not have to load it. */
    private long size;

    /** Media type as the server declared it, e.g. {@code image/jpeg}. */
    private @Nullable String mime;

    /**
     * {@code sha256} of the bytes, hex. Recorded for verification and for a
     * later decision about shared blobs, not used to deduplicate today.
     */
    private @Nullable String contentHash;

    /** Pixel dimensions as the page declared them; {@code 0} when it did not. */
    private int width;

    private int height;

    /**
     * The article that first referenced this image.
     *
     * <p>Only the first: an image can belong to any number of articles, and
     * keeping the full list would make every re-extraction a write. This is
     * for diagnosis — "where did this come from" — not a foreign key.
     */
    private @Nullable String firstArticleId;

    /** {@code LEAD} or {@code INLINE}, as the extractor classified it. */
    private String role = "";

    private Instant firstSeenAt = Instant.EPOCH;

    private @Nullable Instant storedAt;

    // ─── Queue state ───

    private int attempts;

    /**
     * When this image may be attempted again. Doubles as the claim lease:
     * claiming pushes it out, so one field is both the schedule and the lock
     * — the same shape as the source and content queues.
     */
    private @Nullable Instant nextAttemptAt;

    /** Why the last attempt failed. Cleared on success. */
    private @Nullable String error;
}
