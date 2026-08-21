package de.mhus.hrafnagud.centauri;

import de.mhus.hrafnagud.facet.ArchiveFacets;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.enrichment.EnrichmentService;
import de.mhus.hrafnagud.munin.place.PlaceRegistry;
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
 * <p><b>On by default</b> ({@code munin.centauri.enabled}, since the archive is
 * of little use to anyone if serving it takes a second decision). What that
 * makes the operator's job: the endpoints are <b>unauthenticated</b> unless
 * {@code vance.ode.centauri.apiKey} is set, so a bare {@code java -jar} serves
 * {@code /ode/feed} to whoever reaches the port. The bundled deployments pin
 * the switch off and set a key; anything else is announced at WARN on startup
 * rather than left to be discovered.
 *
 * <p>Off is announced too — see {@link CentauriDisabledNotice}.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "munin.centauri", name = "enabled", havingValue = "true")
public class CentauriConfiguration {

    @Bean
    public FeedSource hrafnagudFeedSource(
            ArchiveFacets facets,
            PlaceRegistry places,
            ArticleService articles,
            EnrichmentService enrichments,
            SourceService sources,
            @Value("${vance.ode.centauri.path:/ode/feed}") String path,
            @Value("${vance.ode.centauri.apiKey:}") String apiKey) {

        if (apiKey.isBlank()) {
            // WARN, not INFO: this module is on by default, so the open case is
            // reached by doing nothing at all. An operator scanning for
            // problems must find it without knowing to look for it.
            log.warn("Feed source enabled at '{}' with NO api key — anyone who reaches "
                    + "the path can read the archive. Set vance.ode.centauri.apiKey, "
                    + "or munin.centauri.enabled=false to stop serving it.", path);
        } else {
            log.info("Feed source enabled at '{}' (api key required)", path);
        }
        return new HrafnagudFeedSource(facets, places, articles, enrichments, sources);
    }
}
