package de.mhus.hrafnagud.munin.ingest;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.munin.article.ArticleCandidate;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/** What one read of a source produced. */
@Value
@Builder
public class SourceReadResult {

    FetchOutcome outcome;

    /** HTTP status, {@code 0} when the request never completed. */
    int httpStatus;

    /** Usable entries, URL-normalised and cleaned. */
    @Singular
    List<ArticleCandidate> candidates;

    /** Entries rejected before dedup — no link, unusable URL, no title. */
    int invalidCount;

    /** Language the feed declared for itself, if any. */
    @Nullable String feedLanguage;

    /** Title the feed declared, used to fill in an unnamed source. */
    @Nullable String feedTitle;

    /** Validators to replay on the next poll. */
    @Nullable String etag;

    @Nullable String lastModified;

    /**
     * New home of this feed, when a permanent redirect moved it. Null on
     * every ordinary read.
     */
    @Nullable String movedTo;

    @Nullable String error;

    static SourceReadResult failure(FetchOutcome outcome, int status, String error) {
        return SourceReadResult.builder()
                .outcome(outcome)
                .httpStatus(status)
                .error(error)
                .build();
    }
}
