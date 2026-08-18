package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.article.ArticleContentDto;
import de.mhus.hrafnagud.api.article.ArticleDto;
import de.mhus.hrafnagud.api.article.ContentStatus;
import de.mhus.hrafnagud.api.common.PageDto;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.error.BadRequestException;
import de.mhus.hrafnagud.munin.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reading the archive. */
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ArticleService articleService;

    /**
     * Lists articles, newest first.
     *
     * <p>{@code total} is {@code -1} unless {@code count=true} is asked for.
     * Over a multi-million-row archive an exact count is a full index scan,
     * and paying it on every page turn would make this the slowest endpoint
     * in the service for a number most callers only want once.
     */
    @GetMapping
    public PageDto<ArticleDto> list(
            @RequestParam(value = "source", required = false) @Nullable String source,
            @RequestParam(value = "language", required = false) @Nullable String language,
            @RequestParam(value = "category", required = false) @Nullable String category,
            @RequestParam(value = "q", required = false) @Nullable String text,
            @RequestParam(value = "contentStatus", required = false)
                    @Nullable ContentStatus contentStatus,
            @RequestParam(value = "since", required = false) @Nullable Instant since,
            @RequestParam(value = "until", required = false) @Nullable Instant until,
            @RequestParam(value = "oldestFirst", defaultValue = "false") boolean oldestFirst,
            @RequestParam(value = "count", defaultValue = "false") boolean count,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        if (since != null && until != null && !since.isBefore(until)) {
            throw new BadRequestException("'since' must be before 'until'");
        }

        ArticleQuery filter = ArticleQuery.builder()
                .sourceName(source)
                .language(language)
                .category(category)
                .text(text)
                .contentStatus(contentStatus)
                .since(since)
                .until(until)
                .oldestFirst(oldestFirst)
                .build();

        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        List<ArticleDocument> articles = articleService.search(filter, pageIndex, pageSize);
        long total = count ? articleService.count(filter) : -1;
        return PageDto.of(articles.stream().map(MuninMapper::toDto).toList(),
                pageIndex, pageSize, total);
    }

    @GetMapping("/{id}")
    public ArticleDto get(@PathVariable("id") String id) {
        return MuninMapper.toDto(articleService.requireById(id));
    }

    /**
     * The article body, as a separate resource.
     *
     * <p>404 when the body has not been fetched — the article's
     * {@code contentStatus} says why, and duplicating that reason here would
     * mean two places to keep in step.
     */
    @GetMapping("/{id}/content")
    public ArticleContentDto content(@PathVariable("id") String id) {
        articleService.requireById(id);
        return articleService.findContent(id)
                .map(MuninMapper::toDto)
                .orElseThrow(() -> new NotFoundException("article content", id));
    }

    /** Puts the article back in the body-fetch queue with a fresh budget. */
    @PostMapping("/{id}/fetch-content")
    public ArticleDto requeueContent(@PathVariable("id") String id) {
        articleService.requireById(id);
        articleService.requeueContent(id, Instant.now());
        return MuninMapper.toDto(articleService.requireById(id));
    }

    /**
     * Takes the article out of the body-fetch queue for good.
     *
     * <p>The inverse of {@code fetch-content}. Ingest queues every article
     * regardless of whether the fetcher is running, so excluding one is an
     * explicit decision rather than a side effect of configuration.
     */
    @PostMapping("/{id}/skip-content")
    public ArticleDto skipContent(@PathVariable("id") String id) {
        articleService.requireById(id);
        articleService.skipContent(id, Instant.now());
        return MuninMapper.toDto(articleService.requireById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        articleService.delete(id);
    }
}
