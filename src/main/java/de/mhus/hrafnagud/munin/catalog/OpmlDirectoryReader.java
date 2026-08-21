package de.mhus.hrafnagud.munin.catalog;

import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * The standard shape: an OPML <b>directory</b>.
 *
 * <p>This is not an invention of ours. The OPML 2.0 specification has a
 * section called <i>Directories</i> — "a directory may contain an arbitrary
 * structure of outline elements with type include, link or rss" — and two
 * ways to point at another OPML file:
 *
 * <ul>
 *   <li>{@code <outline type="include" url="…opml"/>} — introduced in OPML
 *       2.0, always points at an OPML file.
 *   <li>{@code <outline type="link" url="…"/>} — if the address ends in
 *       {@code .opml} the spec calls the result <i>inclusion</i>; if it does
 *       not, the link is something for a browser and not for us.
 * </ul>
 *
 * <p>So the list of lists needs no format of its own, and a publisher who
 * follows the spec needs no code here. That is worth stating because the
 * sibling reader exists precisely for publishers who do not.
 *
 * <p><b>One level, not recursive.</b> A directory that includes a directory
 * that includes a directory is representable and would be a fetch storm off a
 * single URL; nesting is flattened for the folder labels, but an entry always
 * becomes a source list rather than another catalogue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpmlDirectoryReader implements CatalogReader {

    public static final String TYPE = "opml-directory";

    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 2_000;

    private final HttpFetcher fetcher;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "OPML directory";
    }

    @Override
    public CatalogReadResult read(SourceCatalogDocument catalog) {
        HttpFetchResult response = fetcher.get(catalog.getUrl());
        if (!response.isSuccess()) {
            throw new CatalogReadException(StringUtils.defaultIfBlank(response.getError(),
                    "HTTP " + response.getStatus()));
        }

        Document document;
        try {
            document = Jsoup.parse(response.bodyAsText(), catalog.getUrl(), Parser.xmlParser());
        } catch (RuntimeException e) {
            throw new CatalogReadException("not parseable as XML: " + e.getMessage(), e);
        }
        Element root = document.selectFirst("opml");
        if (root == null) {
            throw new CatalogReadException(
                    "no <opml> element — is this really an OPML directory?");
        }

        List<CatalogEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int invalid = 0;

        Element body = root.selectFirst("body");
        if (body != null) {
            invalid = walk(body, new ArrayDeque<>(), entries, seen, warnings, 0);
        }
        if (entries.isEmpty()) {
            // Not an exception: a directory may legitimately be empty, and
            // treating that as a failure would disable every list it owns on
            // the first refresh after the publisher cleared it. The
            // reconciliation policy is the place where that is decided.
            warnings.add("the directory contains no OPML entries");
        }
        return new CatalogReadResult(entries, invalid, warnings);
    }

    private int walk(Element parent, Deque<String> labels, List<CatalogEntry> entries,
            Set<String> seen, List<String> warnings, int depth) {

        if (depth > MAX_DEPTH) {
            warnings.add("stopped at nesting depth " + MAX_DEPTH);
            return 0;
        }
        int invalid = 0;
        for (Element outline : parent.children()) {
            if (!"outline".equalsIgnoreCase(outline.tagName())) {
                continue;
            }
            String type = outline.attr("type").trim().toLowerCase(Locale.ROOT);
            String url = StringUtils.trimToEmpty(outline.attr("url"));
            String text = StringUtils.firstNonBlank(outline.attr("text"),
                    outline.attr("title"), url);

            boolean isInclude = "include".equals(type);
            boolean isOpmlLink = "link".equals(type) && endsWithOpml(url);

            if (isInclude || isOpmlLink) {
                if (entries.size() >= MAX_ENTRIES) {
                    warnings.add("stopped after " + MAX_ENTRIES + " entries");
                    return invalid;
                }
                var normalized = UrlNormalizer.normalize(url);
                if (normalized.isEmpty()) {
                    invalid++;
                    log.trace("Catalogue entry with unusable url: {}", url);
                    continue;
                }
                if (!seen.add(normalized.get())) {
                    continue;
                }
                entries.add(new CatalogEntry(
                        keyOf(labels, text, normalized.get()),
                        normalized.get(),
                        text,
                        SourceListType.OPML,
                        null,
                        List.copyOf(labels)));
                continue;
            }

            // A folder: its label becomes a category for everything below it,
            // exactly as it does one layer down in the subscription list.
            if (StringUtils.isBlank(url) && !outline.children().isEmpty()) {
                labels.addLast(StringUtils.defaultIfBlank(text, "?"));
                invalid += walk(outline, labels, entries, seen, warnings, depth + 1);
                labels.removeLast();
            }
        }
        return invalid;
    }

    private static boolean endsWithOpml(String url) {
        String withoutQuery = StringUtils.substringBefore(url, "?");
        return withoutQuery.toLowerCase(Locale.ROOT).endsWith(".opml");
    }

    /**
     * The key the include/exclude globs match. Folder labels are part of it,
     * so a directory that groups by topic can be filtered by topic without
     * the publisher having done anything for us.
     */
    private static String keyOf(Deque<String> labels, String text, String url) {
        String leaf = StringUtils.defaultIfBlank(text, url);
        return labels.isEmpty() ? leaf : String.join("/", labels) + "/" + leaf;
    }
}
