package de.mhus.hrafnagud.munin.content;

import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Recovers the article text and images from a publisher's page.
 *
 * <p>A news page is mostly not the article: navigation, related-story
 * rails, newsletter pitches, comment scaffolding and consent banners
 * typically outweigh the prose several times over. Separating the two is
 * genuinely hard, and the failure is <em>silent</em> — a navigation rail
 * stored as article text throws no exception and is discovered months
 * later. So the strategy is to ask before guessing, and to record which
 * happened.
 *
 * <p>Four rungs, in order:
 *
 * <ol>
 *   <li>{@code json-ld} — the publisher's own {@code schema.org} metadata.
 *       Not a heuristic at all: it is an answer. See {@link JsonLdReader}.</li>
 *   <li>{@code semantic} — a container the page marks as its article body
 *       ({@code <article>}, {@code itemprop=articleBody}, …).</li>
 *   <li>{@code scored} — the container holding the most paragraph text
 *       relative to its link density.</li>
 *   <li>{@code body} — last resort.</li>
 * </ol>
 *
 * <p>Link density is what makes the scoring rung work. Navigation and
 * related-article blocks are text-heavy too, but nearly all of their text
 * sits inside anchors, while article prose sits nearly all outside them.
 * That single ratio separates the two more reliably than any word count.
 *
 * <p>Metadata is merged across rungs independently of the body: even when
 * JSON-LD carries no {@code articleBody} — which is common — its headline,
 * image, date and language still beat anything derived from the DOM.
 *
 * <p>Stateless and IO-free, so extraction quality can be developed against
 * saved pages rather than against the live web.
 */
@Component
public class ContentExtractor {

    /** Removed outright: never article text, regardless of where they sit. */
    private static final String NOISE_TAGS =
            "script, style, noscript, nav, header, footer, aside, form, iframe, svg, "
                    + "button, select, textarea, template";

    /**
     * Class and id fragments marking a container as chrome, matched as whole
     * hyphen- or underscore-delimited words so that {@code article-body} is
     * not caught by {@code ad}.
     *
     * <p>Multilingual on purpose. A collector that is explicitly worldwide
     * cannot recognise chrome only in English — {@code werbung},
     * {@code publicidad} and {@code pubblicità} are exactly as common on
     * their respective sites as {@code advertisement} is on English ones,
     * and missing them means storing ad copy as news.
     */
    private static final Pattern NOISE_ATTRIBUTE = Pattern.compile(
            "(^|[-_\\s])("
                    // structure and navigation
                    + "nav|menu|footer|header|masthead|sidebar|side-bar|breadcrumb|widget|"
                    // advertising
                    + "ad|ads|advert|advertisement|ad-slot|werbung|anzeige|anzeigen|"
                    + "publicite|publicidad|pubblicita|reklama|"
                    // sharing and social
                    + "share|sharing|social|teilen|compartir|partager|condividi|"
                    // comments
                    + "comment|comments|kommentar|kommentare|comentarios|commentaires|"
                    // related content and teasers
                    + "related|recirc|teaser|promo|verwandte|relacionados|correlati|"
                    + "mehr-zum-thema|lesen-sie-auch|"
                    // subscription and consent
                    + "newsletter|subscribe|subscription|abo|paywall|cookie|consent|"
                    + "zustimmung|datenschutz|banner|popup|modal|"
                    // page furniture
                    + "meta|tags|author-box"
                    + ")([-_\\s]|$)",
            Pattern.CASE_INSENSITIVE);

    /** Containers a page offers when it marks up its own article body. */
    private static final List<String> SEMANTIC_SELECTORS = List.of(
            "[itemprop=articleBody]",
            "[property=articleBody]",
            "article",
            "main article",
            "main",
            "[role=main]",
            ".article-body",
            ".articleBody",
            ".article__body",
            ".post-content",
            ".entry-content",
            ".story-body",
            "#article-body");

    /** Blocks kept from the chosen container, in document order. */
    private static final String CONTENT_BLOCKS = "p, h2, h3, h4, li, blockquote, pre";

    /** A paragraph shorter than this is a caption or a byline, not prose. */
    private static final int MIN_PARAGRAPH_CHARS = 25;

    /**
     * Below this, a JSON-LD {@code articleBody} is treated as a description
     * that was put in the wrong field rather than as the article, and the
     * DOM rungs are used instead.
     */
    private static final int MIN_JSONLD_BODY_WORDS = 50;

    /**
     * Images smaller than this in either declared dimension are icons,
     * spacers and tracking pixels. Images that declare no dimensions at all
     * are kept — most do not, and requiring them would discard almost
     * everything.
     */
    private static final int MIN_IMAGE_DIMENSION = 100;

    /** Attributes holding the real image URL, best first. */
    private static final List<String> IMAGE_SRC_ATTRIBUTES = List.of(
            "data-src", "data-original", "data-lazy-src", "data-url", "src");

    /** Attributes holding a candidate set; the widest candidate wins. */
    private static final List<String> IMAGE_SRCSET_ATTRIBUTES = List.of("srcset", "data-srcset");

    /** URL fragments that mark a lazy-loading placeholder rather than an image. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "data:image", "placeholder", "blank.gif", "blank.png", "spacer.gif",
            "transparent.png", "1x1.", "pixel.gif", "lazy.gif");

    /**
     * Phrases indicating the body is gated. Full phrases in several
     * languages rather than single words: "subscribe" alone appears in the
     * newsletter box of nearly every news page ever published.
     */
    private static final List<String> PAYWALL_PHRASES = List.of(
            "subscribe to continue", "subscribers only", "already a subscriber",
            "to continue reading", "this article is for subscribers",
            "nur für abonnenten", "jetzt abonnieren und weiterlesen", "weiterlesen mit",
            "artikel für abonnenten", "réservé aux abonnés", "abonnez-vous pour lire",
            "para suscriptores", "suscríbete para seguir leyendo",
            "solo per abbonati", "continua a leggere con");

    /**
     * Extracts from {@code html}.
     *
     * @param baseUri URL the page was fetched from, so relative links
     *                resolve to absolute ones
     */
    public ExtractedArticle extract(String html, String baseUri) {
        Document document = Jsoup.parse(html, baseUri);

        // Read everything that lives outside the article body before the
        // noise strip runs — paywall markers and meta tags sit in exactly
        // the containers that strip removes.
        JsonLdArticle jsonLd = JsonLdReader.read(document);
        boolean gated = looksGated(document);
        String canonicalUrl = canonicalOf(document);
        String title = titleOf(document, jsonLd);
        String language = TextCleaner.normalizeLanguage(languageOf(document, jsonLd));
        String author = authorOf(document, jsonLd);
        Instant publishedAt = publishedAtOf(document, jsonLd);
        ExtractedImage leadImage = leadImageOf(document, jsonLd, baseUri);

        stripNoise(document);
        Container container = chooseContainer(document);

        // Harvest images before captions are removed, since the caption is
        // what makes an inline image worth having.
        List<ExtractedImage> inlineImages = inlineImagesOf(container.element(), baseUri);
        container.element().select("figcaption").remove();

        String text;
        String extractor;
        if (jsonLd.getArticleBody() != null
                && TextCleaner.wordCount(jsonLd.getArticleBody()) >= MIN_JSONLD_BODY_WORDS) {
            // The publisher stated the body. Publishers that emit this field
            // at all emit the whole thing, so it is preferred over our own
            // reading of their markup even when the DOM would yield more —
            // "more" is as likely to be chrome we failed to strip.
            text = jsonLd.getArticleBody();
            extractor = "json-ld";
        } else {
            text = textOf(container.element());
            extractor = container.name();
        }

        List<ExtractedImage> images = new ArrayList<>();
        if (leadImage != null) {
            images.add(leadImage);
        }
        Set<String> seen = new LinkedHashSet<>();
        if (leadImage != null) {
            seen.add(leadImage.getUrl());
        }
        for (ExtractedImage image : inlineImages) {
            if (seen.add(image.getUrl())) {
                images.add(image);
            }
        }

        return ExtractedArticle.builder()
                .text(text)
                .wordCount(TextCleaner.wordCount(text))
                .title(StringUtils.trimToNull(title))
                .images(images)
                .language(language)
                .author(StringUtils.trimToNull(author))
                .publishedAt(publishedAt)
                .canonicalUrl(StringUtils.trimToNull(canonicalUrl))
                .extractor(extractor)
                .gated(gated)
                .build();
    }

    /** The chosen container together with the rung that chose it. */
    private record Container(Element element, String name) {
    }

    private Container chooseContainer(Document document) {
        Element semantic = bestSemanticCandidate(document);
        if (semantic != null) {
            return new Container(semantic, "semantic");
        }
        Element scored = bestScoredCandidate(document);
        if (scored != null) {
            return new Container(scored, "scored");
        }
        Element body = document.body();
        return new Container(body == null ? document : body, "body");
    }

    /** Drops chrome so it cannot win the scoring or leak into the text. */
    private static void stripNoise(Document document) {
        document.select(NOISE_TAGS).remove();
        for (Element element : document.select("[class], [id]")) {
            if (NOISE_ATTRIBUTE.matcher(element.className()).find()
                    || NOISE_ATTRIBUTE.matcher(element.id()).find()) {
                element.remove();
            }
        }
    }

    /**
     * The best container the page marks up itself.
     *
     * <p>Semantic markup is believed, but not blindly: several of these
     * selectors also match a wrapper holding the article <em>and</em> the
     * rest of the page, so the winner is still the highest scoring. A match
     * with no prose in it is rejected, which is what happens on sites whose
     * {@code <main>} is a single-page-app shell.
     */
    private static @Nullable Element bestSemanticCandidate(Document document) {
        Element best = null;
        double bestScore = 0;
        for (String selector : SEMANTIC_SELECTORS) {
            for (Element element : document.select(selector)) {
                double score = score(element);
                if (score > bestScore) {
                    bestScore = score;
                    best = element;
                }
            }
        }
        return bestScore > 0 ? best : null;
    }

    private static @Nullable Element bestScoredCandidate(Document document) {
        Element best = null;
        double bestScore = 0;
        for (Element element : document.select("div, section, td")) {
            double score = score(element);
            if (score > bestScore) {
                bestScore = score;
                best = element;
            }
        }
        return best;
    }

    /**
     * How much this element looks like article prose. Paragraph length
     * contributes with diminishing returns, so one enormous block does not
     * outweigh a genuine article of many paragraphs, and the whole score is
     * scaled down by link density.
     */
    private static double score(Element element) {
        double score = 0;
        for (Element paragraph : element.select("p")) {
            int length = paragraph.text().length();
            if (length < MIN_PARAGRAPH_CHARS) {
                continue;
            }
            score += 1 + Math.min(length, 1000) / 25.0;
        }
        if (score <= 0) {
            return 0;
        }
        return score * (1 - linkDensity(element));
    }

    /**
     * Fraction of an element's text sitting inside anchors. Near 1 for
     * navigation and related-story lists, near 0 for prose.
     */
    private static double linkDensity(Element element) {
        int total = element.text().length();
        if (total == 0) {
            return 1;
        }
        int linked = 0;
        for (Element anchor : element.select("a")) {
            linked += anchor.text().length();
        }
        return Math.min(1.0, (double) linked / total);
    }

    /**
     * Joins the content blocks of the chosen container with blank lines.
     * Nested blocks are skipped — a {@code <li>} inside a
     * {@code <blockquote>} would otherwise be emitted twice.
     */
    private static String textOf(@Nullable Element container) {
        if (container == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        Elements blocks = container.select(CONTENT_BLOCKS);
        for (Element block : blocks) {
            if (hasAncestorIn(block, blocks)) {
                continue;
            }
            String line = TextCleaner.normalizeWhitespace(block.text());
            if (line.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(line);
        }
        if (text.length() == 0) {
            return TextCleaner.normalizeWhitespace(container.text());
        }
        return text.toString();
    }

    private static boolean hasAncestorIn(Element element, Elements candidates) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (candidates.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    // ─── Images ───

    /**
     * The declared representative image: JSON-LD first, then Open Graph,
     * then Twitter cards.
     */
    private static @Nullable ExtractedImage leadImageOf(Document document, JsonLdArticle jsonLd,
            String baseUri) {

        for (String candidate : jsonLd.getImages()) {
            String url = absolute(baseUri, candidate);
            if (isUsableImageUrl(url)) {
                return ExtractedImage.builder().url(url).role(ImageRole.LEAD).build();
            }
        }
        for (String selector : List.of("meta[property=og:image]", "meta[name=og:image]",
                "meta[name=twitter:image]", "meta[property=twitter:image]")) {
            String url = document.select(selector).attr("abs:content");
            if (isUsableImageUrl(url)) {
                return ExtractedImage.builder().url(url).role(ImageRole.LEAD).build();
            }
        }
        return null;
    }

    /**
     * Images inside the article body, with their captions.
     *
     * <p>Position is the signal: an image inside the container that survived
     * chrome-stripping and won the scoring is part of the reporting, while
     * one outside it is decoration. That is a far better discriminator than
     * anything about the image itself.
     */
    private static List<ExtractedImage> inlineImagesOf(Element container, String baseUri) {
        List<ExtractedImage> images = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Element img : container.select("img")) {
            String url = resolveImageUrl(img, baseUri);
            if (!isUsableImageUrl(url) || !seen.add(url)) {
                continue;
            }
            int width = dimension(img, "width");
            int height = dimension(img, "height");
            if (isTooSmall(width, height)) {
                continue;
            }
            String caption = captionOf(img);
            if (!looksLikeContent(img, caption, width, height)) {
                continue;
            }
            images.add(ExtractedImage.builder()
                    .url(url)
                    .caption(caption)
                    .role(ImageRole.INLINE)
                    .width(width)
                    .height(height)
                    .build());
        }
        return images;
    }

    /**
     * Rejects page furniture that happens to sit inside the article
     * container.
     *
     * <p>An image that is part of the reporting carries at least one signal
     * that somebody meant it to be looked at: it is wrapped in a
     * {@code <figure>}, it has a caption or alt text, or it declares a
     * content-sized geometry. Promotional graphics — a newspaper's own
     * front-page thumbnail advertising a subscription is the archetype —
     * have none of the three, because nothing about them is meant to be read.
     *
     * <p>Measured against real pages this drops the promo images while
     * keeping every captioned photograph; roughly nine in ten genuine
     * inline images carry a caption or alt text on their own.
     */
    private static boolean looksLikeContent(Element img, @Nullable String caption,
            int width, int height) {

        if (StringUtils.isNotBlank(caption)) {
            return true;
        }
        if (width >= MIN_IMAGE_DIMENSION || height >= MIN_IMAGE_DIMENSION) {
            return true;
        }
        for (Element parent = img.parent(); parent != null; parent = parent.parent()) {
            if ("figure".equalsIgnoreCase(parent.tagName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the real image URL.
     *
     * <p>Lazy loading is the trap here: on a large share of news sites
     * {@code src} holds a transparent placeholder and the actual image is in
     * {@code data-src} or {@code srcset}. Reading {@code src} first would
     * fill the archive with references to the same blank GIF.
     */
    private static String resolveImageUrl(Element img, String baseUri) {
        for (String attribute : IMAGE_SRCSET_ATTRIBUTES) {
            String widest = widestFromSrcset(img.attr(attribute));
            if (!widest.isEmpty()) {
                String url = absolute(baseUri, widest);
                if (isUsableImageUrl(url)) {
                    return url;
                }
            }
        }
        for (String attribute : IMAGE_SRC_ATTRIBUTES) {
            String value = img.attr(attribute);
            if (StringUtils.isBlank(value)) {
                continue;
            }
            String url = absolute(baseUri, value);
            if (isUsableImageUrl(url)) {
                return url;
            }
        }
        return "";
    }

    /** Picks the widest candidate from a {@code srcset} value. */
    private static String widestFromSrcset(String srcset) {
        if (StringUtils.isBlank(srcset)) {
            return "";
        }
        String best = "";
        long bestWidth = -1;
        for (String entry : srcset.split(",")) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            long width = 0;
            if (parts.length > 1 && parts[1].endsWith("w")) {
                try {
                    width = Long.parseLong(parts[1].substring(0, parts[1].length() - 1));
                } catch (NumberFormatException e) {
                    width = 0;
                }
            }
            if (width > bestWidth || best.isEmpty()) {
                bestWidth = width;
                best = parts[0];
            }
        }
        return best;
    }

    /**
     * Caption from the enclosing {@code <figure>}, falling back to the
     * {@code alt} text.
     */
    private static @Nullable String captionOf(Element img) {
        for (Element parent = img.parent(); parent != null; parent = parent.parent()) {
            if ("figure".equalsIgnoreCase(parent.tagName())) {
                Element caption = parent.selectFirst("figcaption");
                if (caption != null && StringUtils.isNotBlank(caption.text())) {
                    return captionTextOf(caption);
                }
                break;
            }
        }
        String alt = TextCleaner.normalizeWhitespace(img.attr("alt"));
        return alt.isEmpty() ? null : alt;
    }

    /**
     * Reads a {@code <figcaption>}, keeping its parts apart.
     *
     * <p>Captions routinely wrap the photo credit in an inline element right
     * after the description, with no whitespace between them in the markup.
     * Plain text extraction then yields "…shows the site.picture
     * alliance/dpa" as one run-on string. Separating element boundaries with
     * a space before collapsing whitespace keeps the credit legible without
     * having to recognise what a credit looks like.
     */
    private static String captionTextOf(Element caption) {
        Element copy = caption.clone();
        for (Element child : copy.select("*")) {
            child.prependText(" ");
        }
        return TextCleaner.normalizeWhitespace(copy.text());
    }

    private static int dimension(Element img, String attribute) {
        String value = img.attr(attribute).replaceAll("[^0-9]", "");
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Undeclared dimensions are not treated as small — most pages omit them. */
    private static boolean isTooSmall(int width, int height) {
        return (width > 0 && width < MIN_IMAGE_DIMENSION)
                || (height > 0 && height < MIN_IMAGE_DIMENSION);
    }

    private static boolean isUsableImageUrl(@Nullable String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private static String absolute(String baseUri, String url) {
        String value = StringUtils.trimToEmpty(url);
        if (value.isEmpty() || StringUtils.isBlank(baseUri)) {
            return value;
        }
        try {
            return URI.create(baseUri).resolve(value).toString();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    // ─── Metadata ───

    private static String titleOf(Document document, JsonLdArticle jsonLd) {
        if (StringUtils.isNotBlank(jsonLd.getHeadline())) {
            return jsonLd.getHeadline();
        }
        String ogTitle = document.select("meta[property=og:title]").attr("content");
        if (StringUtils.isNotBlank(ogTitle)) {
            return TextCleaner.stripHtml(ogTitle);
        }
        Element h1 = document.selectFirst("h1");
        if (h1 != null && StringUtils.isNotBlank(h1.text())) {
            return TextCleaner.normalizeWhitespace(h1.text());
        }
        return TextCleaner.normalizeWhitespace(document.title());
    }

    private static @Nullable String languageOf(Document document, JsonLdArticle jsonLd) {
        if (StringUtils.isNotBlank(jsonLd.getLanguage())) {
            return jsonLd.getLanguage();
        }
        Element html = document.selectFirst("html[lang]");
        if (html != null && StringUtils.isNotBlank(html.attr("lang"))) {
            return html.attr("lang");
        }
        return StringUtils.trimToNull(
                document.select("meta[property=og:locale]").attr("content"));
    }

    private static String authorOf(Document document, JsonLdArticle jsonLd) {
        if (StringUtils.isNotBlank(jsonLd.getAuthor())) {
            return jsonLd.getAuthor();
        }
        for (String selector : List.of("meta[name=author]", "meta[property=article:author]")) {
            String value = document.select(selector).attr("content");
            if (StringUtils.isNotBlank(value)) {
                return TextCleaner.stripHtml(value);
            }
        }
        Element byline = document.selectFirst("[rel=author]");
        return byline == null ? "" : TextCleaner.normalizeWhitespace(byline.text());
    }

    private static @Nullable Instant publishedAtOf(Document document, JsonLdArticle jsonLd) {
        if (jsonLd.getDatePublished() != null) {
            return jsonLd.getDatePublished();
        }
        for (String selector : List.of("meta[property=article:published_time]",
                "meta[name=article:published_time]", "meta[itemprop=datePublished]",
                "time[datetime]")) {
            Element element = document.selectFirst(selector);
            if (element == null) {
                continue;
            }
            String value = StringUtils.defaultIfBlank(element.attr("content"),
                    element.attr("datetime"));
            Instant parsed = parseInstant(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static @Nullable Instant parseInstant(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String canonicalOf(Document document) {
        return document.select("link[rel=canonical]").attr("abs:href");
    }

    /**
     * Checks for gating before the noise strip runs — the markers usually
     * live in exactly the containers that strip removes.
     */
    private static boolean looksGated(Document document) {
        if (!document.select("[class*=paywall], [id*=paywall], [data-paywall]").isEmpty()) {
            return true;
        }
        String text = document.text().toLowerCase(Locale.ROOT);
        for (String phrase : PAYWALL_PHRASES) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
