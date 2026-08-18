package de.mhus.hrafnagud.munin.content;

import de.mhus.hrafnagud.munin.util.TextCleaner;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Recovers the article text from a publisher's page.
 *
 * <p>A news page is mostly not the article: navigation, related-story
 * rails, newsletter pitches, comment scaffolding and consent banners
 * typically outweigh the prose several times over. Extraction is therefore
 * a two-stage guess — believe the page's own semantics when it offers any,
 * and otherwise score containers on how much uninterrupted paragraph text
 * they hold relative to how much of it is links.
 *
 * <p>Link density is what makes the scoring work. Navigation and related-
 * article blocks are text-heavy too, but nearly all of their text sits
 * inside anchors, while article prose is nearly all outside them. That
 * single ratio separates the two more reliably than any word count.
 *
 * <p>Stateless and IO-free, so extraction quality can be developed against
 * saved pages rather than against the live web.
 */
@Component
public class ContentExtractor {

    /** Removed outright: never article text, regardless of where they sit. */
    private static final String NOISE_TAGS =
            "script, style, noscript, nav, header, footer, aside, form, iframe, svg, "
                    + "button, select, textarea, template, figure > figcaption";

    /**
     * Class and id fragments that mark a container as chrome. Matched as a
     * whole word-ish substring so that {@code article-body} is not caught by
     * {@code ad}.
     */
    private static final Pattern NOISE_ATTRIBUTE = Pattern.compile(
            "(^|[-_\\s])(comment|share|sharing|social|related|recirc|promo|newsletter|"
                    + "subscribe|subscription|cookie|consent|banner|sidebar|side-bar|advert|"
                    + "advertisement|ad-slot|ads|breadcrumb|nav|menu|footer|header|masthead|"
                    + "teaser|widget|paywall|popup|modal|meta|tags|author-box)([-_\\s]|$)",
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
     * Phrases that indicate the body is gated. Deliberately full phrases in
     * several languages rather than single words: "subscribe" alone appears
     * in the newsletter box of nearly every news page ever published.
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
     * @param baseUri URL the page was fetched from, so relative image links
     *                resolve to absolute ones
     */
    public ExtractedArticle extract(String html, String baseUri) {
        Document document = Jsoup.parse(html, baseUri);

        String title = titleOf(document);
        String imageUrl = imageOf(document);
        String language = TextCleaner.normalizeLanguage(languageOf(document));
        boolean gated = looksGated(document);

        stripNoise(document);

        Element candidate = bestSemanticCandidate(document);
        String extractor = "semantic";
        if (candidate == null) {
            candidate = bestScoredCandidate(document);
            extractor = "scored";
        }
        if (candidate == null) {
            candidate = document.body();
            extractor = "body";
        }

        String text = textOf(candidate);
        return ExtractedArticle.builder()
                .text(text)
                .wordCount(TextCleaner.wordCount(text))
                .title(StringUtils.trimToNull(title))
                .imageUrl(StringUtils.trimToNull(imageUrl))
                .language(language)
                .extractor(extractor)
                .gated(gated)
                .build();
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
     * selectors also match a wrapper that holds the article <em>and</em> the
     * rest of the page, so the winner is still the one that scores highest.
     * A match with no prose in it is rejected outright, which is what
     * happens on sites whose {@code <main>} is a single-page-app shell.
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

    /** Falls back to scoring every plausible container in the page. */
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
     * How much this element looks like article prose.
     *
     * <p>Paragraph length contributes with diminishing returns, so one
     * enormous block does not outweigh a genuine article of many
     * paragraphs, and the whole score is scaled down by link density.
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
     * Fraction of an element's text that sits inside anchors. Near 1 for
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
     *
     * <p>Nested blocks are skipped — a {@code <li>} inside a
     * {@code <blockquote>} would otherwise be emitted twice, once as part of
     * its parent and once on its own.
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

    private static String titleOf(Document document) {
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

    private static String imageOf(Document document) {
        for (String selector : List.of("meta[property=og:image]", "meta[name=twitter:image]")) {
            // absUrl resolves against the base URI the document was parsed
            // with, so a relative image path becomes usable.
            String url = document.select(selector).attr("abs:content");
            if (StringUtils.isNotBlank(url)) {
                return url;
            }
        }
        return "";
    }

    private static @Nullable String languageOf(Document document) {
        Element html = document.selectFirst("html[lang]");
        if (html != null && StringUtils.isNotBlank(html.attr("lang"))) {
            return html.attr("lang");
        }
        String ogLocale = document.select("meta[property=og:locale]").attr("content");
        return StringUtils.trimToNull(ogLocale);
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
