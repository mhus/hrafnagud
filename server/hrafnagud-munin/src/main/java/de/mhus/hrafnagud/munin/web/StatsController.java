package de.mhus.hrafnagud.munin.web;

import de.mhus.hrafnagud.api.common.MuninStatsDto;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.source.SourceService;
import de.mhus.hrafnagud.munin.sourcelist.SourceListService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One call that answers "is it collecting, and from where".
 *
 * <p>Aggregates rather than caches. The counts run over indexed fields and
 * this endpoint is polled by a human or a dashboard, not by the ingest
 * path — a cache here would add staleness to numbers whose whole purpose is
 * to be current.
 */
@RestController
@RequiredArgsConstructor
public class StatsController {

    private final SourceService sourceService;
    private final SourceListService listService;
    private final ArticleService articleService;

    @GetMapping("/api/v1/stats")
    public MuninStatsDto stats() {
        Instant now = Instant.now();
        return MuninStatsDto.builder()
                .sourcesTotal(sourceService.countAll())
                .sourcesEnabled(sourceService.countEnabled())
                .sourcesFailing(sourceService.countFailing())
                .sourceListsTotal(listService.countAll())
                .articlesTotal(articleService.countAll())
                .articlesLast24h(articleService.countSince(now.minus(Duration.ofHours(24))))
                .articlesByContentStatus(articleService.countByContentStatus())
                .articlesByLanguage(articleService.countByLanguage())
                .newestArticleAt(articleService.newestArticleAt().orElse(null))
                .oldestArticleAt(articleService.oldestArticleAt().orElse(null))
                .serverTime(now)
                .build();
    }
}
