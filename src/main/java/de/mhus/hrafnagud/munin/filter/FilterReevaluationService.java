package de.mhus.hrafnagud.munin.filter;

import de.mhus.hrafnagud.api.filter.FilterOutcomes;
import de.mhus.hrafnagud.api.filter.FilterReevaluationReport;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.munin.source.SourceService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Applies the current rules to articles that are already stored.
 *
 * <p>Rules change and articles do not arrive twice, so without this the
 * {@code TOPIC} rule type would be near-useless — the topics it matches are
 * resolved asynchronously, after the article was filtered — and every rule
 * written today would only ever apply to tomorrow's news.
 *
 * <p>Started by hand, bounded twice: by a time window and by a cap. The archive
 * is millions of rows and a full pass is not a routine operation.
 */
@Service
@Slf4j
public class FilterReevaluationService {

    private final ArticleService articleService;
    private final ArticleFilterService filterService;
    private final FilterRuleRegistry registry;
    private final SourceService sourceService;
    private final Settings.Filter config;

    public FilterReevaluationService(ArticleService articleService,
            ArticleFilterService filterService, FilterRuleRegistry registry,
            SourceService sourceService, Settings settings) {
        this.articleService = articleService;
        this.filterService = filterService;
        this.registry = registry;
        this.sourceService = sourceService;
        this.config = settings.getFilter();
    }

    /**
     * @param since oldest article to consider, null for the whole archive
     * @param max   cap on articles examined; the configured maximum when null
     *              or out of range
     */
    public FilterReevaluationReport reevaluate(@Nullable Instant since, @Nullable Integer max) {
        Instant runStartedAt = Instant.now();
        int cap = max == null || max <= 0 || max > config.maxPerRun().value()
                ? config.maxPerRun().value()
                : max;

        // Loaded once for the whole run rather than per article. The profile
        // lives on the source, and a lookup per article would make a walk over
        // a hundred thousand articles a hundred thousand extra reads for a
        // value that changes when somebody edits a catalogue.
        Map<String, String> profiles = new HashMap<>(sourceService.fetchProfilesByName());

        long examined = 0;
        long changed = 0;
        long denied = 0;
        long accepted = 0;
        boolean capped = false;

        while (examined < cap) {
            int batch = (int) Math.min(config.batchSize().value(), cap - examined);
            List<ArticleDocument> articles =
                    articleService.nextForPolicy(since, runStartedAt, batch);
            if (articles.isEmpty()) {
                break;
            }
            for (ArticleDocument article : articles) {
                FilterOutcomes outcomes = filterService.evaluate(subjectOf(article, profiles));
                ArticleService.PolicyUpdate update =
                        articleService.applyPolicy(article, outcomes, runStartedAt);
                examined++;
                if (update.decisionChanged()) {
                    changed++;
                }
                if (update.queuedOut()) {
                    denied++;
                }
                if (update.queuedIn()) {
                    accepted++;
                }
            }
            // A short batch means the window is exhausted; a full one that
            // reached the cap means there is more to do next time.
            if (articles.size() < batch) {
                break;
            }
            capped = examined >= cap;
        }

        log.info("Filter re-evaluation: examined {}, changed {}, out of queues {}, back in {}{}",
                examined, changed, denied, accepted, capped ? " (capped)" : "");
        return FilterReevaluationReport.builder()
                .since(since)
                .examined(examined)
                .changed(changed)
                .denied(denied)
                .accepted(accepted)
                .capped(capped)
                .rulesApplied(registry.size())
                .build();
    }

    /**
     * The stored article as rule input.
     *
     * <p>Everything but the profile is on the article, which is the point of
     * having denormalised the place path and the topics onto it: re-evaluating
     * a rule about Asia or about sport reads one document.
     */
    private static FilterSubject subjectOf(ArticleDocument article, Map<String, String> profiles) {
        String profile = null;
        for (String source : article.getSourceNames()) {
            profile = profiles.get(source);
            if (profile != null) {
                break;
            }
        }
        return FilterSubject.of(article.getUrl(), article.getSourceNames(), article.getLanguage(),
                article.getOriginPlaceIds(), article.getCategories(), article.getTopicIds(),
                profile);
    }
}
