package de.mhus.hrafnagud.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate state of the archive — the operator's single-glance answer to
 * "is it collecting, and from where".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MuninStatsDto {

    private long sourcesTotal;

    private long sourcesEnabled;

    /** Sources whose last poll failed. The number to watch. */
    private long sourcesFailing;

    private long sourceListsTotal;

    private long articlesTotal;

    /** Articles first seen in the last 24 hours. */
    private long articlesLast24h;

    /** Article count per body-fetch state, keyed by {@code ContentStatus}. */
    private Map<String, Long> articlesByContentStatus = new LinkedHashMap<>();

    /**
     * Articles still owing at least one translation. Climbing steadily
     * means nothing is draining the queue.
     */
    private long translationBacklog;

    /** Article count per {@code TranslationStatus}. */
    private Map<String, Long> articlesByTranslationStatus = new LinkedHashMap<>();

    /** Translation enrichments stored — more than one per article after a re-run. */
    private long enrichmentsTotal;

    /**
     * Image count per {@code ImageStatus}. Empty while
     * {@code munin.image.enabled} has never been on — nothing is queued then,
     * so there is nothing to count.
     */
    private Map<String, Long> imagesByStatus = new LinkedHashMap<>();

    /**
     * Bytes held across all stored image copies. The number that decides
     * whether the switch stays on: the copies live in MongoDB documents, so
     * this is database volume rather than disk somewhere else.
     */
    private long imageBytesStored;

    /** Article count per language, most frequent first, top 20. */
    private Map<String, Long> articlesByLanguage = new LinkedHashMap<>();

    private @Nullable Instant newestArticleAt;

    private @Nullable Instant oldestArticleAt;

    private Instant serverTime = Instant.EPOCH;
}
