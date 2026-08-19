package de.mhus.hrafnagud.api.article;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One collected news item, without its body — the body is a separate
 * resource because it is roughly fifty times larger and almost never wanted
 * in a list response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleDto {

    private String id = "";

    /** Article URL, normalised. Identity of the article. */
    private String url = "";

    /** URL exactly as the feed delivered it, tracking parameters intact. */
    private @Nullable String originalUrl;

    private String title = "";

    /** Teaser from the feed, HTML stripped. */
    private @Nullable String summary;

    private @Nullable String author;

    private @Nullable String imageUrl;

    // ─── Classification ───

    /** BCP-47 primary subtag, or {@code null} when undetermined. */
    private @Nullable String language;

    private LanguageSource languageSource = LanguageSource.UNKNOWN;

    /**
     * Categories exactly as feed and source declared them, never normalised
     * into a taxonomy of ours. Publishers disagree wildly about what a
     * category is; folding them together at ingest would destroy
     * information that cannot be recovered.
     */
    private List<String> categories = new ArrayList<>();

    // ─── Provenance ───

    /** Every source that delivered this article, in order of first arrival. */
    private List<String> sources = new ArrayList<>();

    /** The source that delivered it first. */
    private String firstSource = "";

    // ─── Time ───

    /**
     * Publication time as claimed by the feed, {@code null} when absent or
     * implausible. Never used for ordering — publishers backdate, forward-date
     * and time-zone this field wrong often enough that a single bad feed
     * would dominate any sort built on it.
     */
    private @Nullable Instant publishedAt;

    /** When we first saw it. Authoritative for ordering and windowing. */
    private Instant firstSeenAt = Instant.EPOCH;

    /**
     * When the most recent source was added — the last time a feed that did
     * not already have this article delivered it. Together with
     * {@code sources} it shows how far a story travelled and how fast.
     * Equal to {@code firstSeenAt} when only one source ever carried it.
     */
    private @Nullable Instant lastSourceAddedAt;

    // ─── Body state ───

    private ContentStatus contentStatus = ContentStatus.PENDING;

    private @Nullable Instant contentFetchedAt;

    /** Words in the extracted body. Zero until fetched. */
    private int contentWordCount;

    private @Nullable String contentError;

    /**
     * Target languages still owed. Non-empty means the article is queued
     * for translation; empty means either done or never queued — which of
     * the two is answered by {@link #translations}.
     */
    private List<String> pendingTranslations = new ArrayList<>();

    /**
     * Translations keyed by BCP-47 primary subtag. Deliberately a map rather
     * than a pair of {@code titleEn}/{@code titleDe} fields: the third
     * target language must not be a schema change.
     */
    private Map<String, ArticleTranslationDto> translations = new LinkedHashMap<>();
}
