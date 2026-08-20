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
 * <p>An empty hrafnagud has nothing to collect from, and pasting OPML URLs in
 * by hand is not a starting point. So one catalogue ships:
 * {@code awesome-rss-feeds}, a CC0 collection of 66 OPML files — 25 by country,
 * 41 by topic, together about 840 feeds.
 *
 * <p><b>Installed disabled.</b> A catalogue is a standing instruction to crawl
 * somebody else's list of publishers — those 840 feeds are roughly 1,700
 * requests an hour leaving this machine — and more catalogues will ship beside
 * this one. An installation that starts all of them the moment it boots is a
 * surprise for whoever runs it and for everyone being crawled. So the
 * catalogue is present, visible in the console, and one click from running;
 * choosing which of them to switch on is the operator's, and it needs no
 * configuration file.
 *
 * <p>Installed <b>once</b>, keyed by name. A catalogue an operator then
 * enabled, filtered or deleted stays that way across restarts: re-asserting
 * bundled configuration on every boot would make local decisions impossible to
 * keep — and would switch a catalogue somebody turned off back on.
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
                // Not enabled: see the class comment. The console turns it on.
                .enabled(false)
                .include(properties.getCatalog().getBundledInclude())
                .build();
        try {
            catalogService.create(request, Instant.now());
            log.info("Installed the bundled catalog '{}' ({}), disabled — enable it in the "
                            + "console at /console/#catalogs to start collecting{}",
                    NAME, URL,
                    properties.getCatalog().getBundledInclude().isEmpty()
                            ? ""
                            : " (filtered to "
                                    + properties.getCatalog().getBundledInclude() + ")");
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
