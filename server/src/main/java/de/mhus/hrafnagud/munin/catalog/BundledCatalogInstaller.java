package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.catalog.CatalogCreateRequest;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Installs the catalogues a fresh instance starts with.
 *
 * <p>An empty hrafnagud has nothing to collect from, and pasting OPML URLs in
 * by hand is not a starting point. So {@code awesome-rss-feeds} ships — a CC0
 * collection of 66 OPML files in one repository.
 *
 * <p><b>Two catalogues, one repository.</b> The collection mixes kinds: its
 * {@code countries/} half lists news outlets, its {@code recommended/} half
 * lists blogs by topic — Programming, Personal finance, Chess. Those want
 * different poll intervals by two orders of magnitude, and a catalogue carries
 * exactly one fetch profile, so one catalogue cannot describe both honestly.
 * Splitting it by the filter that already exists gives each half its own
 * profile, and lets an operator run the news half without the blogs.
 *
 * <p>That is also why a catalogue's URL is not unique — see
 * {@link SourceCatalogDocument}.
 *
 * <p><b>Installed disabled.</b> A catalogue is a standing instruction to crawl
 * somebody else's list of publishers — the news half alone is a few hundred
 * feeds — and an installation that starts crawling the moment it boots is a
 * surprise for whoever runs it and for everyone being crawled. Both are
 * present, visible in the console, and one switch from running.
 *
 * <p>Installed <b>once</b>, keyed by name, and skipped entirely when a
 * catalogue for this repository already exists under any name: an instance
 * that was set up before the split keeps what it has, because re-asserting
 * bundled configuration would undo local decisions — and would quietly add a
 * second catalogue over lists somebody is already collecting.
 */
@Component
@ConditionalOnProperty(name = "munin.catalog.installBundled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BundledCatalogInstaller {

    static final String URL = "https://github.com/plenaryapp/awesome-rss-feeds";

    /** Name used before the news/blog split, still honoured as "already set up". */
    static final String LEGACY_NAME = "awesome-rss-feeds";

    private final SourceCatalogService catalogService;

    @PostConstruct
    public void install() {
        for (Bundled bundled : bundled()) {
            install(bundled);
        }
    }

    private void install(Bundled bundled) {
        if (catalogService.findByName(bundled.name).isPresent()
                || catalogService.findByName(LEGACY_NAME).isPresent()) {
            return;
        }
        CatalogCreateRequest request = CatalogCreateRequest.builder()
                .name(bundled.name)
                .title(bundled.title)
                .type(GithubOpmlReader.TYPE)
                .url(URL)
                .params(paths(bundled.path))
                .include(List.of(bundled.path + "/**"))
                .fetchProfile(bundled.profile)
                // The console turns it on; see the class comment.
                .enabled(false)
                .build();
        try {
            catalogService.create(request, Instant.now());
            log.info("Installed the bundled catalog '{}' ({}, profile {}), disabled — enable "
                            + "it at /console/#catalogs to start collecting",
                    bundled.name, bundled.path, bundled.profile);
        } catch (RuntimeException e) {
            // Never fatal: a collector that will not start because a
            // convenience catalogue could not be written is worse than one
            // that starts without it. A second instance racing us here is the
            // normal case, not an error.
            log.info("Bundled catalog {} not installed: {}", bundled.name, e.toString());
        }
    }

    /**
     * The two halves.
     *
     * <p>Only the {@code with_category} directories. The repository also
     * carries {@code without_category} copies of the same feeds; importing both
     * would double every list and drop the folder labels that become an
     * article's categories.
     */
    private static List<Bundled> bundled() {
        return List.of(
                new Bundled("awesome-rss-feeds-news", "awesome-rss-feeds — news (CC0)",
                        "countries/with_category", "news"),
                new Bundled("awesome-rss-feeds-blogs", "awesome-rss-feeds — blogs (CC0)",
                        "recommended/with_category", "blog"));
    }

    private static Map<String, String> paths(String path) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(GithubOpmlReader.PARAM_PATHS, path);
        return params;
    }

    private record Bundled(String name, String title, String path, String profile) { }
}
