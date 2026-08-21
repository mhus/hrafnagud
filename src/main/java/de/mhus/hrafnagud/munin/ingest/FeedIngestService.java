package de.mhus.hrafnagud.munin.ingest;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.SourceFetchReport;
import de.mhus.hrafnagud.api.source.SourceType;
import de.mhus.hrafnagud.munin.article.ArticleCandidate;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.lang.LanguageResolver;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.source.SourceService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Polls one source and files what it returned.
 *
 * <p>The step between reading and storing: resolve each entry's language,
 * hand it to the archive, queue it for the body fetcher, and fold the
 * per-entry outcomes into the source's schedule and statistics.
 *
 * <p>Deliberately synchronous and per-source. Concurrency lives one level
 * up in {@link FeedIngestTick}, which means this method can be called
 * directly by the manual fetch endpoint and by tests without a scheduler,
 * and that a failure affects exactly one source.
 */
@Service
@Slf4j
public class FeedIngestService {

    private final SourceService sourceService;
    private final ArticleService articleService;
    private final LanguageResolver languageResolver;
    private final Map<SourceType, SourceReader> readers = new EnumMap<>(SourceType.class);

    public FeedIngestService(SourceService sourceService, ArticleService articleService,
            LanguageResolver languageResolver, List<SourceReader> readerBeans) {
        this.sourceService = sourceService;
        this.articleService = articleService;
        this.languageResolver = languageResolver;
        for (SourceReader reader : readerBeans) {
            readers.put(reader.type(), reader);
        }
    }

    /** Polls {@code source} and records the outcome. */
    public SourceFetchReport poll(SourceDocument source, Instant now) {
        long startedAt = System.currentTimeMillis();
        SourceReader reader = readers.get(source.getType());
        if (reader == null) {
            throw new IllegalStateException("no reader registered for " + source.getType());
        }

        SourceReadResult read = reader.read(source);
        SourceFetchReport.SourceFetchReportBuilder report = SourceFetchReport.builder()
                .sourceName(source.getName())
                .outcome(read.getOutcome())
                .httpStatus(read.getHttpStatus())
                .itemsRead(read.getCandidates().size())
                .itemsInvalid(read.getInvalidCount())
                .error(read.getError());

        int created = 0;
        int sameSource = 0;
        int crossSource = 0;
        int failed = 0;

        if (read.getOutcome() == FetchOutcome.OK) {
            // Every ingested article is queued for the body fetcher,
            // unconditionally. Whether that queue is worked is the fetcher's
            // business, not ours: a producer that consulted the consumer's
            // configuration would bake a runtime decision into stored state,
            // and switching the fetcher on later would leave every article
            // collected until then permanently unfetchable.
            ContentStatus initialStatus = ContentStatus.PENDING;

            for (ArticleCandidate candidate : read.getCandidates()) {
                // One article per attempt, and one article per failure.
                //
                // Without this catch a single unstorable entry aborted the whole
                // poll: the loop unwound, the fetch result was never recorded,
                // and the caller booked the source as a fetch error — so a feed
                // lost not that entry but every entry, on every tick, for as long
                // as the entry stayed in its window. That is how one Japanese
                // article took eight languages out of the archive; the text-index
                // stemmer was the trigger, and this is the shape that made it
                // fatal. What we cannot store, we count and say.
                try {
                    LanguageResolver.Resolution language = languageResolver.resolve(
                            source.getLanguage(),
                            StringUtils.defaultIfBlank(candidate.getDeclaredLanguage(),
                                    read.getFeedLanguage()),
                            detectionText(candidate));
                    switch (articleService.ingest(candidate, source, language, initialStatus, now)) {
                        case CREATED -> created++;
                        case DUPLICATE_CROSS_SOURCE -> crossSource++;
                        case DUPLICATE_SAME_SOURCE -> sameSource++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Ingest of '{}' from {} failed, skipping this entry: {}",
                            candidate.getUrl(), source.getName(), e.toString());
                }
            }
            if (failed > 0) {
                // At WARN as well as in the report: a feed that quietly drops a
                // tenth of its entries looks like a feed with fewer entries.
                log.warn("Polled {}: {} of {} entries could not be stored",
                        source.getName(), failed, read.getCandidates().size());
            }
            if (created > 0) {
                sourceService.recordArticles(source.getName(), created, now);
            }
        }

        long nextInterval = sourceService.recordFetchResult(source.getName(), read.getOutcome(),
                created, read.getError(), read.getEtag(), read.getLastModified(),
                source.getFetchIntervalSeconds(), now);

        // Where the next poll should go. Both directions matter: a feed that
        // moved is remembered so the redirect is walked once rather than
        // hourly, and a remembered location that stops working is forgotten
        // so the source falls back to its own identity instead of failing
        // against a URL nobody configured.
        if (read.getMovedTo() != null
                && !read.getMovedTo().equals(source.getFetchUrl())) {
            sourceService.recordMovedTo(source.getName(), read.getMovedTo(), now);
        } else if (!read.getOutcome().successful() && source.getFetchUrl() != null) {
            sourceService.clearFetchUrl(source.getName(), now);
        }

        SourceFetchReport finished = report
                // Counted with the entries the reader rejected: from the outside
                // both are "the feed offered it and it is not in the archive".
                .itemsInvalid(read.getInvalidCount() + failed)
                .articlesCreated(created)
                .duplicatesInSource(sameSource)
                .duplicatesCrossSource(crossSource)
                .nextIntervalSeconds(nextInterval)
                .nextFetchAt(now.plusSeconds(nextInterval))
                .durationMillis(System.currentTimeMillis() - startedAt)
                .build();

        if (read.getOutcome().successful()) {
            log.debug("Polled {}: {} read, {} new, {} dup-same, {} dup-cross, next in {}s",
                    source.getName(), finished.getItemsRead(), created, sameSource, crossSource,
                    nextInterval);
        } else {
            log.info("Poll of {} failed ({}): {} — next attempt in {}s", source.getName(),
                    read.getOutcome(), read.getError(), nextInterval);
        }
        return finished;
    }

    /**
     * Text handed to language detection: title and teaser together.
     *
     * <p>A headline alone is short enough that statistical detection starts
     * guessing, and the resolver's minimum-length threshold would reject
     * most of them. The teaser is usually what pushes an entry over it.
     */
    private static String detectionText(ArticleCandidate candidate) {
        return candidate.getTitle() + " " + StringUtils.defaultString(candidate.getSummary());
    }
}
