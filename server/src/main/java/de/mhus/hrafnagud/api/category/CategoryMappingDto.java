package de.mhus.hrafnagud.api.category;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the mapping table.
 *
 * <p>Served so the table can be <b>looked at</b>, which is not a nicety: the
 * design leans on a person being able to see that {@code standard} was guessed
 * rather than known, and to say what it should have been. A learning table
 * nobody can inspect is a table nobody can correct.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryMappingDto {

    /** Normalised form — the identity, and what the correction endpoints take. */
    private String key = "";

    /** What the publisher wrote. The reason the normalisation being lossy is safe. */
    private String raw = "";

    private CategoryMappingStatus status = CategoryMappingStatus.NEW;

    private @Nullable String topicId;

    /** Readable name of {@link #topicId}, resolved from the vocabulary. */
    private @Nullable String topicName;

    /** That topic and everything containing it, outermost first. */
    @Builder.Default
    private List<String> topicPath = new ArrayList<>();

    /** Names along {@link #topicPath}, so a client need not join anything. */
    @Builder.Default
    private List<String> topicPathNames = new ArrayList<>();

    private double confidence;

    /** {@code LABEL_EXACT}, {@code LABEL_TOKENS}, {@code LABEL_WORD}, {@code LLM}, {@code HUMAN}. */
    private @Nullable String decidedBy;

    private @Nullable String note;

    /**
     * How many articles carry this category. The sort order that matters —
     * fixing the mapping used two thousand times beats fixing the one used once.
     */
    private long useCount;

    private int attempts;

    private @Nullable String lastError;

    private @Nullable Instant lastSeenAt;
}
