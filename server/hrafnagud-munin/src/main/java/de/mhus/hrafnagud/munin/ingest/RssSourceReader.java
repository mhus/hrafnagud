package de.mhus.hrafnagud.munin.ingest;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndLink;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.api.source.SourceType;
import de.mhus.hrafnagud.munin.article.ArticleCandidate;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.munin.source.SourceDocument;
import de.mhus.hrafnagud.munin.util.TextCleaner;
import de.mhus.hrafnagud.munin.util.UrlNormalizer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads RSS and Atom feeds.
 *
 * <p>Rome handles the dialect zoo — RSS 0.91 through 2.0, RDF, Atom 0.3 and
 * 1.0 — which is the bulk of the work and none of the interesting part.
 * What is left here is deciding what each dialect's fields actually mean,
 * and that is where feeds are inconsistent enough to need explicit rules:
 * which element holds the link, whether the teaser is in
 * {@code description} or {@code content}, where the image is, and which of
 * three date fields to believe.
 *
 * <p>Bytes are handed to Rome's {@link XmlReader} rather than a decoded
 * string. A feed's real encoding is declared in the XML prolog as often as
 * in the HTTP header, the two disagree regularly, and only a reader that
 * sees the raw bytes can apply the precedence rules that resolve it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RssSourceReader implements SourceReader {

    private final HttpFetcher fetcher;
    private final MuninProperties properties;

    @Override
    public SourceType type() {
        return SourceType.RSS;
    }

    @Override
    public SourceReadResult read(SourceDocument source) {
        HttpFetchResult response = fetcher.get(source.getUrl(), source.getHttpEtag(),
                source.getHttpLastModified());

        if (response.isNotModified()) {
            return SourceReadResult.builder()
                    .outcome(FetchOutcome.NOT_MODIFIED)
                    .httpStatus(response.getStatus())
                    .etag(response.getEtag())
                    .lastModified(response.getLastModified())
                    .build();
        }
        if (!response.isSuccess()) {
            return SourceReadResult.failure(FetchOutcome.FETCH_ERROR, response.getStatus(),
                    StringUtils.defaultIfBlank(response.getError(),
                            "HTTP " + response.getStatus()));
        }

        SyndFeed feed;
        try {
            feed = parse(response);
        } catch (FeedException | IOException | IllegalArgumentException e) {
            return SourceReadResult.failure(FetchOutcome.PARSE_ERROR, response.getStatus(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return toResult(feed, response);
    }

    private SyndFeed parse(HttpFetchResult response) throws FeedException, IOException {
        SyndFeedInput input = new SyndFeedInput();
        // A feed is a document from an untrusted host. Doctypes enable
        // external-entity resolution, which turns parsing into an outbound
        // request of the publisher's choosing.
        input.setAllowDoctypes(false);
        // Lenient: real feeds contain undeclared entities and stray control
        // characters, and rejecting the document loses every entry in it.
        input.setXmlHealerOn(true);

        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(response.getBody()),
                StringUtils.defaultString(response.getContentType()), true)) {
            return input.build(reader);
        }
    }

    private SourceReadResult toResult(SyndFeed feed, HttpFetchResult response) {
        MuninProperties.Feed config = properties.getFeed();
        Instant now = Instant.now();

        // RSS declares the language once for the whole channel and Atom
        // carries it as an xml:lang that Rome surfaces the same way. There
        // is no reliable per-entry language in either dialect, so every
        // candidate inherits the feed's — and the resolver treats that as a
        // publisher claim, not as fact.
        String feedLanguage = TextCleaner.normalizeLanguage(feed.getLanguage());

        SourceReadResult.SourceReadResultBuilder result = SourceReadResult.builder()
                .outcome(FetchOutcome.OK)
                .httpStatus(response.getStatus())
                .feedLanguage(feedLanguage)
                .feedTitle(TextCleaner.truncate(TextCleaner.stripHtml(feed.getTitle()), 500))
                .etag(response.getEtag())
                .lastModified(response.getLastModified());

        int invalid = 0;
        int taken = 0;
        for (SyndEntry entry : feed.getEntries()) {
            if (taken >= config.getMaxItemsPerFeed()) {
                break;
            }
            Optional<ArticleCandidate> candidate = toCandidate(entry, config, feedLanguage, now);
            if (candidate.isEmpty()) {
                invalid++;
                continue;
            }
            result.candidate(candidate.get());
            taken++;
        }
        return result.invalidCount(invalid).build();
    }

    /**
     * Maps one entry, or rejects it.
     *
     * <p>An entry is rejected only when it has no usable link or no title.
     * Both are load-bearing: without a link there is no identity and nothing
     * to fetch, and without a title there is nothing to show. Everything
     * else may be missing.
     */
    private Optional<ArticleCandidate> toCandidate(SyndEntry entry, MuninProperties.Feed config,
            @Nullable String feedLanguage, Instant now) {

        String rawLink = linkOf(entry);
        Optional<String> url = UrlNormalizer.normalize(rawLink);
        if (url.isEmpty()) {
            return Optional.empty();
        }

        String title = TextCleaner.truncate(TextCleaner.stripHtml(entry.getTitle()), 1000);
        if (StringUtils.isBlank(title)) {
            return Optional.empty();
        }

        String rawBody = bodyOf(entry);
        String summary = TextCleaner.truncate(TextCleaner.stripHtml(rawBody),
                config.getMaxSummaryChars());

        return Optional.of(ArticleCandidate.builder()
                .url(url.get())
                .originalUrl(StringUtils.abbreviate(rawLink, 2000))
                .title(title)
                .summary(StringUtils.trimToNull(summary))
                .author(StringUtils.trimToNull(
                        TextCleaner.truncate(TextCleaner.stripHtml(entry.getAuthor()), 200)))
                .imageUrl(imageOf(entry, rawBody))
                .guid(StringUtils.trimToNull(StringUtils.abbreviate(entry.getUri(), 500)))
                .publishedAt(TextCleaner.sanitizePublished(dateOf(entry), now,
                        config.getMaxFutureSkew().getSeconds()))
                .declaredLanguage(feedLanguage)
                .categories(categoriesOf(entry))
                .build());
    }

    /**
     * The entry's link.
     *
     * <p>{@code link} first, then an {@code alternate} relation, then
     * {@code uri}. The last is a fallback rather than a preference: in Atom
     * {@code id} is a permanent identifier that is often but not always a
     * URL, and in RSS a {@code guid} with {@code isPermaLink="false"} is
     * explicitly not one. Preferring it would silently store identifiers as
     * article locations.
     */
    private static String linkOf(SyndEntry entry) {
        if (StringUtils.isNotBlank(entry.getLink())) {
            return entry.getLink();
        }
        for (SyndLink link : entry.getLinks()) {
            if ("alternate".equalsIgnoreCase(link.getRel()) && StringUtils.isNotBlank(link.getHref())) {
                return link.getHref();
            }
        }
        for (SyndLink link : entry.getLinks()) {
            if (StringUtils.isNotBlank(link.getHref())) {
                return link.getHref();
            }
        }
        return StringUtils.defaultString(entry.getUri());
    }

    /**
     * The richest text the entry carries.
     *
     * <p>Atom's {@code content} and RSS's {@code content:encoded} usually
     * hold more than {@code description}, but not always — some publishers
     * put a one-line stub in {@code content} and the real teaser in
     * {@code description}. Taking whichever is longer gets it right in both
     * cases without having to know which dialect produced it.
     */
    private static String bodyOf(SyndEntry entry) {
        String best = StringUtils.defaultString(
                entry.getDescription() == null ? null : entry.getDescription().getValue());
        for (SyndContent content : entry.getContents()) {
            String value = StringUtils.defaultString(content.getValue());
            if (value.length() > best.length()) {
                best = value;
            }
        }
        return best;
    }

    /**
     * Lead image: an image enclosure if the entry declares one, otherwise
     * the first {@code <img>} in the body.
     */
    private static @Nullable String imageOf(SyndEntry entry, String body) {
        for (SyndEnclosure enclosure : entry.getEnclosures()) {
            String mime = StringUtils.lowerCase(enclosure.getType(), Locale.ROOT);
            if (mime != null && mime.startsWith("image/")
                    && StringUtils.isNotBlank(enclosure.getUrl())) {
                return UrlNormalizer.normalize(enclosure.getUrl()).orElse(null);
            }
        }
        if (StringUtils.isBlank(body) || !body.contains("<img")) {
            return null;
        }
        Element img = Jsoup.parseBodyFragment(body).selectFirst("img[src]");
        return img == null ? null : UrlNormalizer.normalize(img.attr("src")).orElse(null);
    }

    /**
     * Publication date, preferring the original over the last update — a
     * corrected typo should not move an article to the top of the day.
     */
    private static @Nullable Instant dateOf(SyndEntry entry) {
        Date published = entry.getPublishedDate();
        if (published != null) {
            return published.toInstant();
        }
        Date updated = entry.getUpdatedDate();
        return updated == null ? null : updated.toInstant();
    }

    private static List<String> categoriesOf(SyndEntry entry) {
        List<String> categories = new ArrayList<>();
        for (SyndCategory category : entry.getCategories()) {
            String name = TextCleaner.stripHtml(category.getName());
            if (StringUtils.isNotBlank(name) && name.length() <= 200) {
                categories.add(name);
            }
        }
        return categories;
    }
}
