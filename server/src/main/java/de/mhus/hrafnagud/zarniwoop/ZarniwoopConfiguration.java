package de.mhus.hrafnagud.zarniwoop;

import de.mhus.hrafnagud.facet.ArchiveFacets;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.vance.ode.zarniwoop.SearchSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the {@link SearchSource} bean, which is what makes the Ode module
 * serve its endpoints — it is conditional on the bean existing.
 *
 * <p>Off by default. The endpoints are unauthenticated unless
 * {@code vance.ode.zarniwoop.apiKey} is set, and a module that starts
 * exposing the archive over HTTP merely by being on the classpath would
 * make that somebody else's surprise. Serving to a reader is a decision an
 * operator makes; collecting is not.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "munin.zarniwoop", name = "enabled", havingValue = "true")
public class ZarniwoopConfiguration {

    @Bean
    public SearchSource hrafnagudSearchSource(
            ArchiveFacets facets,
            ArticleService articles,
            EnrichmentService enrichments,
            @Value("${vance.ode.zarniwoop.path:/ode/search}") String path,
            @Value("${vance.ode.zarniwoop.apiKey:}") String apiKey) {

        log.info("Research source enabled at '{}' ({})", path,
                apiKey.isBlank() ? "NO api key — anyone who reaches the path can read"
                        : "api key required");
        return new HrafnagudSearchSource(facets, articles, enrichments);
    }
}
