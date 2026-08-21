package de.mhus.hrafnagud.jaglan;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Where an archive object lives inside the mount, and how to get back from a
 * path to the object.
 *
 * <h2>Why the tree is partitioned at all</h2>
 * A mount is browsed, and a listing is not free: Jaglan writes one metadata
 * row per file it lists, and its own contract insists a folder's count be
 * honest or absent. A flat directory of several hundred thousand hash-named
 * files satisfies that formally and is unusable in practice — which is the
 * whole reason for the levels below.
 *
 * <p>Partitioned by hour, from measurement rather than taste: at a few
 * thousand articles a day a per-day folder holds four to five thousand
 * entries, and a per-hour folder holds a couple of hundred. Every level of the
 * walk down is bounded — twelve months, thirty-one days, twenty-four hours —
 * so no listing anywhere in the tree is large.
 *
 * <pre>
 * article/2026/08/21/14/68a7c1f2e4b09d3a5c6e7f80.md
 * img/2026/08/21/14/276a1ac0…44ab9d9.jpg
 * </pre>
 *
 * <h2>Two properties that cannot be changed later</h2>
 * <b>The date is {@code firstSeenAt}, in UTC.</b> Not the publication date: a
 * path has to be immutable, and {@code publishedAt} is a publisher-controlled
 * field that a re-extraction can correct — the file would move and every
 * stored link to it would break. A local time zone would move paths too, for a
 * service that could be restarted anywhere.
 *
 * <p><b>The id resolves, the date verifies.</b> The last segment is the
 * object's id, so resolution never needs a date index. The date segments are
 * still checked against the object, because without that check one file would
 * be reachable under every date — and Jaglan would create a metadata row, and
 * therefore a visible duplicate document, for each spelling.
 */
public final class ArchivePath {

    /** The two subtrees. Their names are part of every stored link. */
    public static final String ARTICLES = "article";
    public static final String IMAGES = "img";

    /** Articles are served as Markdown; the extension is part of the contract. */
    public static final String ARTICLE_SUFFIX = ".md";

    private static final DateTimeFormatter PARTITION =
            DateTimeFormatter.ofPattern("yyyy/MM/dd/HH", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * File extension per stored media type.
     *
     * <p>Derived from the type rather than from the source URL: a URL's
     * extension is frequently absent (a CDN path with a query string) or
     * wrong, and a name that says {@code .jpg} over PNG bytes is a file that
     * some readers refuse.
     */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif",
            "image/avif", "avif");

    private ArchivePath() {
    }

    /** What a parsed path points at. */
    public enum Kind {
        ARTICLE, IMAGE
    }

    /**
     * A path taken apart: which subtree, which object, and the partition it
     * claimed.
     *
     * @param kind      subtree the path belongs to
     * @param id        object id — what a lookup needs, and all it needs
     * @param partition the {@code yyyy/MM/dd/HH} the path spelled, to be
     *                  checked against the object that comes back
     */
    public record Ref(Kind kind, String id, String partition) {

        /** {@code true} when {@code firstSeenAt} really is in this partition. */
        public boolean matches(Instant firstSeenAt) {
            return partition.equals(PARTITION.format(firstSeenAt));
        }
    }

    /** Path of one article's Markdown rendering. */
    public static String ofArticle(String articleId, Instant firstSeenAt) {
        return ARTICLES + "/" + PARTITION.format(firstSeenAt) + "/" + articleId + ARTICLE_SUFFIX;
    }

    /**
     * Path of one stored image.
     *
     * <p>An unknown media type yields no path at all rather than an
     * extension-less one: the fetcher only stores types this map knows, so a
     * miss means the two lists have drifted apart, and inventing a name would
     * hide that behind a file nothing can open.
     */
    public static Optional<String> ofImage(String imageId, Instant firstSeenAt,
            @Nullable String mime) {
        String extension = EXTENSIONS.get(StringUtils.lowerCase(StringUtils.trimToEmpty(mime)));
        if (extension == null) {
            return Optional.empty();
        }
        return Optional.of(
                IMAGES + "/" + PARTITION.format(firstSeenAt) + "/" + imageId + "." + extension);
    }

    /**
     * Takes a mount-relative path apart, or empty when it is not one of ours.
     *
     * <p>Strict on purpose. This is reached with whatever a caller typed, and
     * the alternative to rejecting a malformed path is resolving it to
     * something — the shape of every path-traversal bug there has ever been.
     */
    public static Optional<Ref> parse(String path) {
        String[] segments = StringUtils.strip(path, "/").split("/");
        if (segments.length != 6) {
            // subtree / yyyy / MM / dd / HH / file
            return Optional.empty();
        }
        Kind kind = switch (segments[0]) {
            case ARTICLES -> Kind.ARTICLE;
            case IMAGES -> Kind.IMAGE;
            default -> null;
        };
        if (kind == null || !isPartition(segments, 1)) {
            return Optional.empty();
        }
        String id = stripExtension(kind, segments[5]);
        if (!isHexOrObjectId(id)) {
            return Optional.empty();
        }
        String partition = segments[1] + "/" + segments[2] + "/" + segments[3] + "/" + segments[4];
        return Optional.of(new Ref(kind, id, partition));
    }

    /** The partition prefix of a folder path, for listing. */
    public static String partitionOf(Instant firstSeenAt) {
        return PARTITION.format(firstSeenAt);
    }

    private static boolean isPartition(String[] segments, int from) {
        return isDigits(segments[from], 4)
                && isDigits(segments[from + 1], 2)
                && isDigits(segments[from + 2], 2)
                && isDigits(segments[from + 3], 2);
    }

    private static boolean isDigits(String value, int length) {
        return value.length() == length && StringUtils.isNumeric(value);
    }

    private static String stripExtension(Kind kind, String file) {
        if (kind == Kind.ARTICLE) {
            return StringUtils.removeEnd(file, ARTICLE_SUFFIX);
        }
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    /**
     * Both ids are hex: an image's is a SHA-256, an article's a Mongo
     * {@code ObjectId}. Checking the shape here is what keeps a path segment
     * from reaching a query as anything else.
     */
    private static boolean isHexOrObjectId(String id) {
        if (id.length() != 24 && id.length() != 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
