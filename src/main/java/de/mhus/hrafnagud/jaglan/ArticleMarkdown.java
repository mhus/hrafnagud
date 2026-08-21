package de.mhus.hrafnagud.jaglan;

import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleImage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * One article as a Markdown file.
 *
 * <h2>Every article is a file, not only the ones with a body</h2>
 * The feed alone yields a title, a teaser, a source, a language, categories
 * and a link — a document worth reading and citing. The extracted body is an
 * addition to that, not the precondition for it.
 *
 * <p>The alternative was tried in thought and discarded: a mount that only
 * shows articles whose body was fetched looks empty while
 * {@code munin.content.enabled} is off, which reads as "the mount is broken"
 * rather than "that switch is off".
 *
 * <h2>Front matter is the metadata, once</h2>
 * YAML front matter rather than a prose header, because the reader on the
 * other side is as often a model as a person: a fixed key/value block is
 * cheaper to consume than a sentence, and it keeps the body free of things
 * that are not the article.
 *
 * <p>Values are escaped for YAML by quoting and doubling quotes — publisher
 * text contains colons, quotes and newlines, and a title starting with
 * {@code -} would otherwise turn a scalar into a list.
 *
 * <h2>Image links stay as the publisher wrote them</h2>
 * Rewriting them to {@code vance:/_ext/hrafnagud/img/…} is a separate step
 * that needs to know whether a local copy exists, per image. Until that is
 * built the file references the publisher, which is also what it degrades to
 * when no copy was made — so this is the honest default rather than a
 * placeholder.
 */
public final class ArticleMarkdown {

    private ArticleMarkdown() {
    }

    /** Media type of what this class produces. */
    public static final String MIME = "text/markdown";

    /**
     * Renders {@code article}, with its body when one was extracted.
     *
     * @param content the extracted body, or null when none exists yet
     */
    public static String render(ArticleDocument article,
            @Nullable ArticleContentDocument content) {

        StringBuilder out = new StringBuilder(2048);
        String title = StringUtils.defaultIfBlank(
                content == null ? null : content.getExtractedTitle(), article.getTitle());

        out.append("---\n");
        field(out, "title", title);
        // Every source that delivered it, not one: a wire report reaching the
        // archive from six outlets is one article with six deliverers, and
        // naming only the first would misreport how widely it was carried.
        list(out, "sources", article.getSourceNames());
        field(out, "url", article.getUrl());
        field(out, "language", article.getLanguage());
        field(out, "published", article.getPublishedAt());
        // Both timestamps, because they answer different questions and
        // routinely disagree — see ArticleQuery. The collected one is also
        // what this file's own path is derived from.
        field(out, "collected", article.getFirstSeenAt());
        if (content != null) {
            field(out, "author", content.getAuthor());
            field(out, "extractor", content.getExtractor());
            if (content.getWordCount() > 0) {
                out.append("words: ").append(content.getWordCount()).append('\n');
            }
        }
        list(out, "categories", article.getCategories());
        out.append("---\n\n");

        if (StringUtils.isNotBlank(title)) {
            out.append("# ").append(title.strip()).append("\n\n");
        }

        String lead = leadImage(content, article);
        if (lead != null) {
            out.append("![](").append(lead).append(")\n\n");
        }

        String summary = StringUtils.trimToEmpty(article.getSummary());
        String body = content == null ? "" : StringUtils.trimToEmpty(content.getText());
        if (!summary.isEmpty()) {
            // Italic, so a reader can tell the feed's teaser from the article.
            out.append('*').append(summary).append("*\n\n");
        }
        if (!body.isEmpty()) {
            out.append(body).append('\n');
        } else {
            // Said plainly rather than left as an empty file: "no body here"
            // and "the archive is broken" must not look the same.
            out.append("_No article body has been fetched for this article._\n");
        }

        String source = StringUtils.trimToEmpty(article.getUrl());
        if (!source.isEmpty()) {
            out.append("\n---\n\n[Original](").append(source).append(")\n");
        }
        return out.toString();
    }

    /** Rendered bytes, which is what the mount serves and sizes. */
    public static byte[] renderBytes(ArticleDocument article,
            @Nullable ArticleContentDocument content) {
        return render(article, content).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The declared representative image, if the extraction found one.
     *
     * <p>Falls back to the feed's own image: a feed entry frequently carries
     * one where the page's markup hid it behind a lazy-loading attribute.
     */
    private static @Nullable String leadImage(@Nullable ArticleContentDocument content,
            ArticleDocument article) {
        if (content != null) {
            if (StringUtils.isNotBlank(content.getImageUrl())) {
                return content.getImageUrl();
            }
            for (ArticleImage image : content.getImages()) {
                if ("LEAD".equalsIgnoreCase(image.getRole())
                        && StringUtils.isNotBlank(image.getUrl())) {
                    return image.getUrl();
                }
            }
        }
        return StringUtils.trimToNull(article.getImageUrl());
    }

    private static void field(StringBuilder out, String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        String text = value instanceof Instant instant
                ? instant.toString()
                : StringUtils.trimToEmpty(value.toString());
        if (text.isEmpty()) {
            return;
        }
        out.append(key).append(": ").append(quote(text)).append('\n');
    }

    private static void list(StringBuilder out, String key, List<String> values) {
        List<String> usable = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                usable.add(value.strip());
            }
        }
        if (usable.isEmpty()) {
            return;
        }
        out.append(key).append(": [");
        for (int i = 0; i < usable.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(quote(usable.get(i)));
        }
        out.append("]\n");
    }

    /**
     * A double-quoted YAML scalar.
     *
     * <p>Unconditionally quoted, not only when it looks necessary: every value
     * here is publisher-controlled, and the list of characters that change a
     * YAML scalar's meaning is longer than it looks. Newlines are folded to a
     * space because a front-matter value is one line by construction.
     */
    private static String quote(String value) {
        String flat = value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replaceAll("[\\r\\n]+", " ");
        return "\"" + flat + "\"";
    }
}
