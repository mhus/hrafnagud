package de.mhus.hrafnagud.munin.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.SourceType;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.source.SourceService;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * What a poll does with the location a feed says it moved to.
 *
 * <p>Both directions are here, and the second one is the one worth having:
 * a remembered location that stops working has to be forgotten, or a source
 * fails forever against a URL nobody configured and the log blames the
 * publisher.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedIngestRedirectTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

    @Mock
    private SourceService sourceService;

    @Mock
    private ArticleService articleService;

    @Mock
    private LanguageResolver languageResolver;

    /** A reader that returns what the test wants, without HTTP. */
    private record FixedReader(SourceReadResult result) implements SourceReader {
        @Override
        public SourceType type() {
            return SourceType.RSS;
        }

        @Override
        public SourceReadResult read(SourceDocument source) {
            return result;
        }
    }

    private FeedIngestService ingestWith(SourceReadResult result) {
        return new FeedIngestService(sourceService, articleService, languageResolver,
                List.of(new FixedReader(result)));
    }

    private static SourceDocument source(@Nullable String fetchUrl) {
        return SourceDocument.builder()
                .name("the42-ie-01f61b")
                .type(SourceType.RSS)
                .url("https://the42.ie/feed")
                .fetchUrl(fetchUrl)
                .build();
    }

    private static SourceReadResult ok(@Nullable String movedTo) {
        return SourceReadResult.builder()
                .outcome(FetchOutcome.OK)
                .httpStatus(200)
                .movedTo(movedTo)
                .build();
    }

    @Test
    void permanentRedirect_isRemembered() {
        ingestWith(ok("https://www.the42.ie/feed/")).poll(source(null), NOW);

        verify(sourceService).recordMovedTo("the42-ie-01f61b", "https://www.the42.ie/feed/", NOW);
    }

    @Test
    void unchangedLocation_isNotWrittenAgain() {
        // Every successful poll reports it, and every poll writing it would
        // be one update per source per interval for a value that never
        // changes.
        ingestWith(ok("https://www.the42.ie/feed/")).poll(source("https://www.the42.ie/feed/"), NOW);

        verify(sourceService, never()).recordMovedTo(any(), any(), any());
        verify(sourceService, never()).clearFetchUrl(any(), any());
    }

    @Test
    void movedAgain_isFollowed() {
        ingestWith(ok("https://www.the42.ie/rss/")).poll(source("https://www.the42.ie/feed/"), NOW);

        verify(sourceService).recordMovedTo("the42-ie-01f61b", "https://www.the42.ie/rss/", NOW);
    }

    @Test
    void failedPoll_forgetsTheResolvedLocation() {
        ingestWith(SourceReadResult.failure(FetchOutcome.FETCH_ERROR, 404, "HTTP 404"))
                .poll(source("https://www.the42.ie/feed/"), NOW);

        verify(sourceService).clearFetchUrl("the42-ie-01f61b", NOW);
    }

    @Test
    void failedPoll_withoutOne_writesNothing() {
        ingestWith(SourceReadResult.failure(FetchOutcome.FETCH_ERROR, 403, "HTTP 403"))
                .poll(source(null), NOW);

        verify(sourceService, never()).clearFetchUrl(any(), any());
        verify(sourceService, never()).recordMovedTo(any(), any(), any());
    }

    @Test
    void parseError_alsoForgetsIt() {
        // A location that answers with something that is not a feed is as
        // wrong as one that does not answer.
        ingestWith(SourceReadResult.failure(FetchOutcome.PARSE_ERROR, 200, "not xml"))
                .poll(source("https://www.the42.ie/feed/"), NOW);

        verify(sourceService).clearFetchUrl("the42-ie-01f61b", NOW);
    }

    @Test
    void successWithoutARedirect_keepsWhateverIsStored() {
        // A 304 carries no Location, and a source polling happily at its
        // resolved URL must not lose it to that.
        ingestWith(SourceReadResult.builder()
                .outcome(FetchOutcome.NOT_MODIFIED)
                .httpStatus(304)
                .build())
                .poll(source("https://www.the42.ie/feed/"), NOW);

        verify(sourceService, never()).clearFetchUrl(any(), any());
        verify(sourceService, never()).recordMovedTo(any(), any(), any());
    }

    @Test
    void theOutcomeIsStillRecordedNormally() {
        ingestWith(ok("https://www.the42.ie/feed/")).poll(source(null), NOW);

        verify(sourceService).recordFetchResult(eq("the42-ie-01f61b"), eq(FetchOutcome.OK),
                anyInt(), any(), any(), any(), anyLong(), eq(NOW));
    }
}
