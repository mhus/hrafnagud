package de.mhus.hrafnagud.centauri;

import de.mhus.hrafnagud.facet.ArchiveFacets;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.source.SourceService;
import de.mhus.vance.ode.centauri.FeedSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the {@link FeedSource} bean, which is what makes the Ode module
 * serve its endpoints — it is conditional on the bean existing.
 *
 * <p>Off by default. The endpoints are unauthenticated unless
 * {@code vance.ode.centauri.apiKey} is set, and a module that starts
 * exposing the archive over HTTP merely by being on the classpath would
 * make that somebody else's surprise. Serving to a reader is a decision an
 * operator makes; collecting is not.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "munin.centauri", name = "enabled", havingValue = "true")
public class CentauriConfiguration {

    @Bean
    public FeedSource hrafnagudFeedSource(
            ArchiveFacets facets,
            ArticleService articles,
            EnrichmentService enrichments,
            SourceService sources,
            @Value("${vance.ode.centauri.path:/ode/feed}") String path,
            @Value("${vance.ode.centauri.apiKey:}") String apiKey) {

        log.info("Feed source enabled at '{}' ({})", path,
                apiKey.isBlank() ? "NO api key — anyone who reaches the path can read"
                        : "api key required");
        return new HrafnagudFeedSource(facets, articles, enrichments, sources);
    }
}
