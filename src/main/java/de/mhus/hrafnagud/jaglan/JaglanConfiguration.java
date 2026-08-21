package de.mhus.hrafnagud.jaglan;

import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.image.ImageService;
import de.mhus.vance.ode.jaglan.FileSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the {@link FileSource} bean, which is what makes the Ode module
 * serve {@code /ode/files}.
 *
 * <p><b>Off by default</b>, unlike Centauri and Zarniwoop, and the difference
 * is what the endpoint hands out. Those two serve answers assembled from the
 * archive — a timeline, a ranked list — while this one serves <b>file
 * contents</b> under paths a caller can walk. An unguarded path here is a file
 * server, and the Ode module's own documentation says so; a switch that starts
 * on would make that the default state of a bare {@code java -jar}.
 *
 * <p>Being off is not announced. The two that are on by default warn when
 * unguarded, because there the open state is reached by doing nothing; here
 * doing nothing is the closed state, and a service that logs about every
 * feature nobody enabled buries the ones that matter.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "munin.jaglan", name = "enabled", havingValue = "true")
public class JaglanConfiguration {

    @Bean
    public FileSource hrafnagudFileSource(
            ArticleService articles,
            ImageService images,
            @Value("${vance.ode.jaglan.path:/ode/files}") String path,
            @Value("${vance.ode.jaglan.apiKey:}") String apiKey) {

        if (apiKey.isBlank()) {
            log.warn("File mount enabled at '{}' with NO api key — anyone who reaches the "
                    + "path can read every article and image in the archive as files. "
                    + "Set vance.ode.jaglan.apiKey, or munin.jaglan.enabled=false.", path);
        } else {
            log.info("File mount enabled at '{}' (api key required)", path);
        }
        return new ArchiveFileSource(articles, images);
    }
}
