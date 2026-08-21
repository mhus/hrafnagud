package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The unstandardised shape: a repository holding loose OPML files.
 *
 * <p>This exists because the common way to publish a collection of feed lists
 * is not the standard one. {@code plenaryapp/awesome-rss-feeds} keeps 66 OPML
 * files in two directories with no index of any kind — no OPML directory, no
 * manifest, nothing but the file tree. Enumerating it means asking GitHub what
 * is in those directories.
 *
 * <p><b>Generic, not per repository.</b> Everything specific to a collection
 * is configuration: the repository, optionally a ref, and which directories to
 * read. That is the whole point of the split — the well-known collection is a
 * row in a database, and the next one is a form entry rather than a release.
 *
 * <h2>Configuration</h2>
 * <pre>
 * url    = https://github.com/plenaryapp/awesome-rss-feeds
 * params.paths = countries/with_category,recommended/with_category
 * params.ref   = master            (optional; default branch when absent)
 * </pre>
 *
 * <p><b>No recursion into sub-directories</b>, deliberately. Each directory is
 * one API call against a 60-per-hour unauthenticated budget, and a walk of an
 * unknown tree is the kind of thing that spends that budget in one refresh and
 * then fails for an hour. Naming the directories is one line of configuration
 * and makes the cost of a refresh knowable in advance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GithubOpmlReader implements CatalogReader {

    public static final String TYPE = "github-opml";

    public static final String PARAM_PATHS = "paths";
    public static final String PARAM_REF = "ref";

    /** Bound on one refresh, so a mis-typed configuration cannot spend the budget. */
    private static final int MAX_PATHS = 10;

    private static final int MAX_ENTRIES = 2_000;

    private final HttpFetcher fetcher;
    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "GitHub repository of OPML files";
    }

    @Override
    public CatalogReadResult read(SourceCatalogDocument catalog) {
        Repo repo = parseRepo(catalog.getUrl());
        List<String> paths = paths(catalog);
        String ref = StringUtils.trimToNull(catalog.getParams().get(PARAM_REF));

        List<CatalogEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int invalid = 0;

        for (String path : paths) {
            invalid += readDirectory(repo, path, ref, entries, seen, warnings);
            if (entries.size() >= MAX_ENTRIES) {
                warnings.add("stopped after " + MAX_ENTRIES + " entries");
                break;
            }
        }
        return new CatalogReadResult(entries, invalid, warnings);
    }

    private int readDirectory(Repo repo, String path, @Nullable String ref,
            List<CatalogEntry> entries, Set<String> seen, List<String> warnings) {

        String url = "https://api.github.com/repos/" + repo.owner() + "/" + repo.name()
                + "/contents/" + encodePath(path)
                + (ref == null ? "" : "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8));

        HttpFetchResult response = fetcher.get(url);
        if (!response.isSuccess()) {
            // A 403 here is almost always the unauthenticated rate limit, and
            // saying so saves the next person the API documentation.
            String hint = response.getStatus() == 403
                    ? " (GitHub rate limit for unauthenticated requests?)"
                    : "";
            throw new CatalogReadException("cannot list " + path + ": "
                    + StringUtils.defaultIfBlank(response.getError(),
                            "HTTP " + response.getStatus()) + hint);
        }

        JsonNode listing;
        try {
            listing = objectMapper.readTree(response.bodyAsText());
        } catch (RuntimeException e) {
            throw new CatalogReadException(
                    "GitHub answered something that is not JSON for " + path, e);
        }
        if (!listing.isArray()) {
            throw new CatalogReadException(
                    "expected a directory listing at " + path + " — is it a file?");
        }

        int invalid = 0;
        for (JsonNode node : listing) {
            if (!"file".equals(node.path("type").asString(""))) {
                continue;
            }
            String name = node.path("name").asString("");
            SourceListType type = typeOf(name);
            if (type == null) {
                continue;
            }
            String downloadUrl = node.path("download_url").asString("");
            var normalized = UrlNormalizer.normalize(downloadUrl);
            if (normalized.isEmpty()) {
                invalid++;
                log.trace("GitHub entry without usable download_url: {}", name);
                continue;
            }
            String key = StringUtils.defaultIfBlank(node.path("path").asString(""),
                    path + "/" + name);
            if (!seen.add(normalized.get())) {
                continue;
            }
            entries.add(new CatalogEntry(
                    key,
                    normalized.get(),
                    StringUtils.removeEnd(StringUtils.removeEnd(name, ".opml"), ".txt"),
                    type,
                    null,
                    List.of()));
            if (entries.size() >= MAX_ENTRIES) {
                return invalid;
            }
        }
        return invalid;
    }

    /** Extension decides the parser; anything else in the directory is not ours. */
    private static @Nullable SourceListType typeOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".opml") || lower.endsWith(".xml")) {
            return SourceListType.OPML;
        }
        if (lower.endsWith(".txt")) {
            return SourceListType.TEXT;
        }
        return null;
    }

    private List<String> paths(SourceCatalogDocument catalog) {
        String raw = StringUtils.trimToEmpty(catalog.getParams().get(PARAM_PATHS));
        List<String> paths = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = StringUtils.strip(part.trim(), "/");
            if (StringUtils.isNotBlank(trimmed)) {
                paths.add(trimmed);
            }
        }
        if (paths.isEmpty()) {
            // The repository root. Explicit rather than an error: a repository
            // that keeps its lists at the top level is a legitimate shape.
            paths.add("");
        }
        if (paths.size() > MAX_PATHS) {
            throw new CatalogReadException(
                    "at most " + MAX_PATHS + " paths per catalogue, got " + paths.size());
        }
        return paths;
    }

    /** Splits {@code https://github.com/owner/repo} into its two halves. */
    static Repo parseRepo(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new CatalogReadException("not a usable github url: " + url, e);
        }
        String host = StringUtils.defaultString(uri.getHost()).toLowerCase(Locale.ROOT);
        if (!host.equals("github.com") && !host.equals("www.github.com")) {
            throw new CatalogReadException(
                    "the github-opml reader needs a github.com url, got: " + url);
        }
        String[] segments = StringUtils.strip(StringUtils.defaultString(uri.getPath()), "/")
                .split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            throw new CatalogReadException("expected github.com/<owner>/<repo>, got: " + url);
        }
        return new Repo(segments[0], StringUtils.removeEnd(segments[1], ".git"));
    }

    /** Each segment separately: the slashes are structure, not content. */
    private static String encodePath(String path) {
        if (path.isEmpty()) {
            return "";
        }
        List<String> encoded = new ArrayList<>();
        for (String segment : path.split("/")) {
            encoded.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", encoded);
    }

    record Repo(String owner, String name) { }
}
