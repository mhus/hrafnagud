package de.mhus.hrafnagud.munin.category;

import de.mhus.hrafnagud.api.category.CategoryMappingStatus;
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
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One publisher's category, and what it was decided to mean.
 *
 * <p>Keyed by the normalised raw string, so a decision is made <b>once for the
 * whole archive</b> rather than per article. That is what makes a model call
 * affordable here: the cost follows the size of the vocabulary — 7,365 entries
 * for an archive of 21,000 articles — and not the volume of news.
 */
@Document(collection = "category_mappings")
@CompoundIndexes({
        @CompoundIndex(name = "category_key_idx", def = "{ 'key': 1 }", unique = true),
        // The queue. Partial, so it holds the backlog rather than the whole
        // vocabulary — the same shape the content and translation queues use.
        @CompoundIndex(name = "category_queue_idx", def = "{ 'nextAttemptAt': 1 }",
                partialFilter = "{ 'status': { $in: ['NEW', 'GUESSED'] } }"),
        @CompoundIndex(name = "category_status_idx", def = "{ 'status': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryMappingDocument {

    @Id
    private @Nullable String id;

    /** Normalised form — the identity. See {@link CategoryKeys}. */
    private String key = "";

    /**
     * What the publisher actually wrote, first spelling seen.
     *
     * <p>Kept because the normalisation is lossy: an operator judging a
     * questionable mapping needs the original, not {@code personal finance}.
     */
    private String raw = "";

    private CategoryMappingStatus status = CategoryMappingStatus.NEW;

    /** Media Topic qcode, when this resolved to one. */
    private @Nullable String topicId;

    /** That topic and everything containing it, outermost first. */
    @Builder.Default
    private List<String> topicPath = new ArrayList<>();

    /** 1.0 for an exact label match down to 0.4 for a single word. */
    private double confidence;

    /** Which stage or rule decided: {@code LABEL_EXACT}, {@code LLM}, … */
    private @Nullable String decidedBy;

    /** Free text from stage two — why it said what it said. */
    private @Nullable String note;

    // ─── Queue state ───

    /** Next eligible attempt; doubles as the claim lease. */
    private @Nullable Instant nextAttemptAt;

    private int attempts;

    private @Nullable String lastError;

    /**
     * How often this category has been seen. Not a statistic for its own sake:
     * it is the order stage two should work in, because resolving the category
     * on two thousand articles matters more than the one used once.
     */
    private long useCount;

    private @Nullable Instant lastSeenAt;

    private Instant createdAt = Instant.EPOCH;

    private Instant updatedAt = Instant.EPOCH;

    @Version
    private @Nullable Long version;
}
