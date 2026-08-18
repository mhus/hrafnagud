package de.mhus.hrafnagud.munin.content;

import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.munin.net.RobotsService;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.time.Instant;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Fetches one article's page and stores the extracted body.
 *
 * <p>Every rejection path assigns a status that says <em>why</em>, because
 * the four reasons call for four different responses: {@code BLOCKED} means
 * stop asking, {@code PAYWALL} means the content is not available to us at
 * all, {@code FAILED} means the retry budget is spent, and staying
 * {@code PENDING} means try again later. Collapsing them into one "error"
 * state would make the backlog impossible to reason about — and would keep
 * retrying pages that will never succeed.
 */
@Service
@Slf4j
public class ContentFetchService {

    /** Response types we can extract from. */
    private static final String HTML_CONTENT_TYPE = "text/html";
    private static final String XHTML_CONTENT_TYPE = "application/xhtml+xml";

    private final HttpFetcher fetcher;
    private final RobotsService robotsService;
    private final ContentExtractor extractor;
    private final ArticleService articleService;
    private final MuninProperties.Content config;

    public ContentFetchService(HttpFetcher fetcher, RobotsService robotsService,
            ContentExtractor extractor, ArticleService articleService,
            MuninProperties properties) {
        this.fetcher = fetcher;
        this.robotsService = robotsService;
        this.extractor = extractor;
        this.articleService = articleService;
        this.config = properties.getContent();
    }

    /**
     * Fetches the body of {@code article} and records the result.
     *
     * @return the status the article ended up in
     */
    public ContentStatus fetch(ArticleDocument article, Instant now) {
        String articleId = StringUtils.defaultString(article.getId());
        int attempts = article.getContentAttempts();

        if (!robotsService.isAllowed(article.getUrl(), now)) {
            articleService.recordContentFailure(articleId, ContentStatus.BLOCKED,
                    "robots.txt disallows this path", attempts, now);
            return ContentStatus.BLOCKED;
        }

        HttpFetchResult response = fetcher.get(article.getUrl());

        if (response.isThrottledOrForbidden()) {
            // The host is actively refusing us. Retrying inside the normal
            // budget only makes it worse, so this ends the attempt.
            articleService.recordContentFailure(articleId, ContentStatus.BLOCKED,
                    "HTTP " + response.getStatus(), attempts, now);
            return ContentStatus.BLOCKED;
        }
        if (!response.isSuccess()) {
            String error = StringUtils.defaultIfBlank(response.getError(),
                    "HTTP " + response.getStatus());
            // 404 and 410 are final; a timeout or a 5xx may not be.
            ContentStatus status = isGone(response.getStatus())
                    ? ContentStatus.FAILED
                    : ContentStatus.PENDING;
            articleService.recordContentFailure(articleId, status, error, attempts, now);
            return status;
        }
        if (!isHtml(response.getContentType())) {
            articleService.recordContentFailure(articleId, ContentStatus.FAILED,
                    "not an HTML document: " + response.getContentType(), attempts, now);
            return ContentStatus.FAILED;
        }

        ExtractedArticle extracted;
        try {
            extracted = extractor.extract(response.bodyAsText(), response.getFinalUrl());
        } catch (RuntimeException e) {
            articleService.recordContentFailure(articleId, ContentStatus.PENDING,
                    "extraction failed: " + e, attempts, now);
            return ContentStatus.PENDING;
        }

        if (extracted.getWordCount() < config.getMinWordCount()) {
            // Too little text to be an article. If the page also carries
            // paywall markers we know why, and retrying is pointless;
            // otherwise it may be a rendering quirk worth one more attempt.
            ContentStatus status = extracted.isGated()
                    ? ContentStatus.PAYWALL
                    : ContentStatus.PENDING;
            articleService.recordContentFailure(articleId, status,
                    "extracted only " + extracted.getWordCount() + " words"
                            + (extracted.isGated() ? " behind a paywall marker" : ""),
                    attempts, now);
            return status;
        }

        ArticleContentDocument content = ArticleContentDocument.builder()
                .articleId(articleId)
                .text(TextCleaner.truncate(extracted.getText(), config.getMaxTextChars()))
                .wordCount(extracted.getWordCount())
                .extractedTitle(extracted.getTitle())
                .imageUrl(extracted.getImageUrl())
                .finalUrl(response.getFinalUrl())
                .extractor(extracted.getExtractor())
                .build();

        articleService.recordContentSuccess(articleId, content, now);
        log.debug("Fetched body for {} — {} words via {}", article.getUrl(),
                extracted.getWordCount(), extracted.getExtractor());
        return ContentStatus.FETCHED;
    }

    /** {@code true} for statuses that say the document will not come back. */
    private static boolean isGone(int status) {
        return status == 404 || status == 410 || status == 451;
    }

    private static boolean isHtml(@org.jspecify.annotations.Nullable String contentType) {
        if (StringUtils.isBlank(contentType)) {
            // Servers that omit Content-Type are common enough that
            // refusing them would drop a real share of the queue. jsoup
            // copes with whatever arrives, and the word-count floor catches
            // the case where it was not HTML after all.
            return true;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith(HTML_CONTENT_TYPE) || type.startsWith(XHTML_CONTENT_TYPE);
    }
}
