package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.catalog.CatalogCreateRequest;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Installs the catalogue a fresh instance starts with.
 *
 * <p>An empty hrafnagud collects nothing, and a collector that needs a
 * curated list of feeds pasted into it before it does anything is not
 * autonomous. So one catalogue ships: {@code awesome-rss-feeds}, a CC0
 * collection of 66 OPML files — 25 by country, 41 by topic, together about
 * 840 feeds.
 *
 * <p><b>Enabled, and therefore actually running.</b> The alternative — install
 * it dormant and wait for a button — would make the first run of every new
 * instance a manual step, which is the opposite of the point. What it costs is
 * real and worth knowing: at the default poll interval those feeds are roughly
 * 1,700 requests an hour leaving this machine. Narrow it with the catalogue's
 * {@code include} filter, or set {@code munin.catalog.installBundled=false} and
 * bring your own.
 *
 * <p>Installed <b>once</b>, keyed by name. A catalogue an operator then
 * disabled, filtered or deleted stays that way across restarts: re-asserting
 * bundled configuration on every boot would make local decisions impossible to
 * keep.
 */
@Component
@ConditionalOnProperty(name = "munin.catalog.installBundled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BundledCatalogInstaller {

    static final String NAME = "awesome-rss-feeds";
    static final String URL = "https://github.com/plenaryapp/awesome-rss-feeds";

    /**
     * Only the {@code with_category} directories. The repository also carries
     * {@code without_category} copies of the same feeds; importing both would
     * double every list and drop the folder labels that become an article's
     * categories.
     */
    static final String PATHS = "countries/with_category,recommended/with_category";

    private final SourceCatalogService catalogService;
    private final MuninProperties properties;

    @PostConstruct
    public void install() {
        if (catalogService.findByName(NAME).isPresent()) {
            return;
        }
        CatalogCreateRequest request = CatalogCreateRequest.builder()
                .name(NAME)
                .title("awesome-rss-feeds (CC0)")
                .type(GithubOpmlReader.TYPE)
                .url(URL)
                .params(params())
                .enabled(true)
                .include(properties.getCatalog().getBundledInclude())
                .build();
        try {
            catalogService.create(request, Instant.now());
            log.info("Installed the bundled catalog '{}' ({}){}", NAME, URL,
                    properties.getCatalog().getBundledInclude().isEmpty()
                            ? " — every list it offers"
                            : " — filtered to "
                                    + properties.getCatalog().getBundledInclude());
        } catch (RuntimeException e) {
            // Never fatal: a collector that will not start because a
            // convenience catalogue could not be written is worse than one
            // that starts without it. A second instance racing us here is the
            // normal case, not an error.
            log.info("Bundled catalog not installed: {}", e.toString());
        }
    }

    private static Map<String, String> params() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(GithubOpmlReader.PARAM_PATHS, PATHS);
        return params;
    }
}
