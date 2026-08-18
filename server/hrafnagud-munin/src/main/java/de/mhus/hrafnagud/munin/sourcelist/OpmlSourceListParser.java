package de.mhus.hrafnagud.munin.sourcelist;

import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.munin.source.SourceCandidate;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * Parses OPML, the format every feed reader and every curated directory
 * exports.
 *
 * <p>OPML nests: an {@code <outline>} without an {@code xmlUrl} is a folder
 * and the ones inside it are its contents. Those folder labels are the only
 * categorisation a directory carries, so they are collected down the
 * ancestor chain and attached to each feed — which is how
 * {@code recommended/with_category/Programming.opml} yields feeds tagged
 * {@code Programming}, and {@code countries/with_category/Germany.opml}
 * yields feeds tagged {@code Germany}.
 *
 * <p>Parsed with jsoup's XML parser rather than a validating one on
 * purpose. Real-world OPML has undeclared entities, mismatched tags and
 * mislabelled encodings; a strict parser rejects a document over one bad
 * character and takes the other nine hundred feeds with it.
 */
@Component
@Slf4j
public class OpmlSourceListParser implements SourceListParser {

    /** Warnings kept in the report; the count is tracked separately. */
    private static final int MAX_WARNINGS = 20;

    /** Guards against a pathological or hand-written document. */
    private static final int MAX_DEPTH = 12;

    @Override
    public SourceListType type() {
        return SourceListType.OPML;
    }

    @Override
    public ParsedSourceList parse(String body, int maxEntries) {
        Document document;
        try {
            document = Jsoup.parse(body, "", Parser.xmlParser());
        } catch (RuntimeException e) {
            throw new SourceListParseException("not parseable as XML: " + e.getMessage(), e);
        }

        Element root = document.selectFirst("opml");
        if (root == null) {
            throw new SourceListParseException("no <opml> element — is this really an OPML export?");
        }

        ParsedSourceList.ParsedSourceListBuilder result = ParsedSourceList.builder();
        result.title(StringUtils.trimToNull(text(document.selectFirst("head > title"))));

        List<String> warnings = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        List<SourceCandidate> entries = new ArrayList<>();
        int invalid = 0;

        Element bodyElement = root.selectFirst("body");
        if (bodyElement != null) {
            invalid = walk(bodyElement, new ArrayDeque<>(), entries, seenUrls, warnings,
                    maxEntries, 0);
        }

        entries.forEach(result::entry);
        warnings.stream().limit(MAX_WARNINGS).forEach(result::warning);
        return result.invalidCount(invalid).build();
    }

    /**
     * Depth-first walk collecting feeds and the folder labels above them.
     *
     * @param categories folder labels of the current ancestor chain
     * @return number of rejected entries
     */
    private int walk(Element parent, Deque<String> categories, List<SourceCandidate> entries,
            Set<String> seenUrls, List<String> warnings, int maxEntries, int depth) {

        if (depth > MAX_DEPTH) {
            warnings.add("outline nesting deeper than " + MAX_DEPTH + " levels — subtree skipped");
            return 0;
        }

        int invalid = 0;
        for (Element outline : parent.children()) {
            if (!"outline".equalsIgnoreCase(outline.tagName())) {
                continue;
            }
            if (entries.size() >= maxEntries) {
                return invalid;
            }

            String feedUrl = firstNonBlank(outline.attr("xmlUrl"), outline.attr("xmlurl"));
            if (StringUtils.isBlank(feedUrl)) {
                // A folder. Its label becomes a category for everything below.
                String label = label(outline);
                if (StringUtils.isNotBlank(label)) {
                    categories.addLast(label);
                }
                invalid += walk(outline, categories, entries, seenUrls, warnings, maxEntries,
                        depth + 1);
                if (StringUtils.isNotBlank(label)) {
                    categories.removeLast();
                }
                continue;
            }

            Optional<String> normalized = UrlNormalizer.normalize(feedUrl);
            if (normalized.isEmpty()) {
                invalid++;
                warnings.add("unusable feed url: " + StringUtils.abbreviate(feedUrl, 120));
                continue;
            }
            if (!seenUrls.add(normalized.get())) {
                // Directories list the same feed under several folders. The
                // first occurrence keeps its categories; counting it as
                // invalid would misreport an ordinary situation.
                continue;
            }

            entries.add(SourceCandidate.builder()
                    .url(normalized.get())
                    .title(TextCleaner.truncate(
                            TextCleaner.stripHtml(label(outline)), 500))
                    .siteUrl(StringUtils.trimToNull(
                            firstNonBlank(outline.attr("htmlUrl"), outline.attr("htmlurl"))))
                    .categories(new ArrayList<>(categories))
                    .language(TextCleaner.normalizeLanguage(outline.attr("language")))
                    .country(StringUtils.trimToNull(outline.attr("country")))
                    .build());

            // An outline may carry both a feed and children. Rare, but legal.
            invalid += walk(outline, categories, entries, seenUrls, warnings, maxEntries,
                    depth + 1);
        }
        return invalid;
    }

    /** OPML spells the label {@code text}; many exporters only set {@code title}. */
    private static String label(Element outline) {
        return firstNonBlank(outline.attr("text"), outline.attr("title"));
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first.trim() : StringUtils.trimToEmpty(second);
    }

    private static String text(@org.jspecify.annotations.Nullable Element element) {
        return element == null ? "" : element.text();
    }
}
