package de.mhus.hrafnagud.jaglan;

import de.mhus.hrafnagud.munin.article.ArticleContentDocument;
import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.image.ImageDocument;
import de.mhus.hrafnagud.munin.image.ImageService;
import de.mhus.vance.ode.jaglan.FileSource;
import de.mhus.vance.ode.jaglan.OdeFileAccess;
import de.mhus.vance.ode.jaglan.OdeFileCapabilities;
import de.mhus.vance.ode.jaglan.OdeFileEntry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * The archive as a file tree.
 *
 * <h2>Folders are computed, not stored</h2>
 * Nothing in MongoDB says "there is a folder for August". The levels are
 * derived from the range of collection timestamps the archive holds: the
 * earliest and the latest are two index reads, and the folders in between are
 * arithmetic. A folder that turns out to hold nothing lists nothing, which is
 * honest and costs one query.
 *
 * <p>That is what keeps a listing cheap enough to be authoritative, which the
 * contract requires it to be: everything omitted is treated by the reader as
 * deleted, so returning a partial page to save time would look like deletion.
 * Every listing here is therefore complete for its folder — bounded by
 * construction at the upper levels (twelve months, thirty-one days,
 * twenty-four hours, sixty minutes) and by the minute partition at the leaf.
 *
 * <h2>Rendered on demand, nothing cached</h2>
 * An article's Markdown is built when asked for. It is derived data with the
 * archive as its single source of truth, and a cache of it would be a second
 * copy to invalidate — the enrichment that arrives an hour later, the body
 * fetched tomorrow. The reader is told a short metadata TTL for the same
 * reason: an article grows a translation and a body after it first appears.
 */
@Slf4j
public class ArchiveFileSource implements FileSource {

    /**
     * How long the reader may cache listings and metadata.
     *
     * <p>Short, because this tree changes underneath it in a way a book
     * library does not: a body arrives, a translation lands, an image is
     * copied — each changing a file's size and content while its path stays.
     */
    private static final Duration METADATA_TTL = Duration.ofMinutes(2);

    private final ArticleService articleService;
    private final ImageService imageService;

    public ArchiveFileSource(ArticleService articleService, ImageService imageService) {
        this.articleService = articleService;
        this.imageService = imageService;
    }

    @Override
    public OdeFileCapabilities capabilities() {
        return new OdeFileCapabilities(
                // Read-only, and it is the source's nature rather than a
                // setting: these files are renderings of collected data. A
                // write would have nowhere to land.
                OdeFileAccess.READ_ONLY,
                true,
                // Both subtrees, because both are files in this mount. Counting
                // only the articles would understate the tree by every image
                // copy the archive holds, and by all of them once image copying
                // has been on for a while.
                articleService.countAll() + imageService.countStored(),
                METADATA_TTL,
                null,
                "Hrafnagud archive");
    }

    // ─── stat ───

    @Override
    public Optional<OdeFileEntry> stat(String path) {
        String clean = normalise(path);
        if (clean.isEmpty()) {
            return Optional.of(OdeFileEntry.folder(""));
        }
        // A folder is anything that parses as a prefix of the layout. Checked
        // before files because it is decidable without a query.
        if (isFolder(clean)) {
            return Optional.of(OdeFileEntry.folder(clean));
        }
        return ArchivePath.parse(clean).flatMap(ref -> switch (ref.kind()) {
            case ARTICLE -> statArticle(ref);
            case IMAGE -> statImage(ref);
        });
    }

    private Optional<OdeFileEntry> statArticle(ArchivePath.Ref ref) {
        return articleService.findById(ref.id())
                // The date has to be the one the path claimed, or one file
                // would answer under every date — see ArchivePath.
                .filter(article -> ref.matches(article.getFirstSeenAt()))
                .map(this::entryFor);
    }

    private Optional<OdeFileEntry> statImage(ArchivePath.Ref ref) {
        return imageService.statById(ref.id())
                .filter(image -> image.getStatus().stored())
                .filter(image -> ref.matches(image.getFirstSeenAt()))
                .flatMap(this::entryFor);
    }

    // ─── list ───

    @Override
    public List<OdeFileEntry> list(String path) {
        String clean = normalise(path);
        if (clean.isEmpty()) {
            return List.of(OdeFileEntry.folder(ArchivePath.ARTICLES),
                    OdeFileEntry.folder(ArchivePath.IMAGES));
        }

        String[] segments = clean.split("/");
        String subtree = segments[0];
        if (!ArchivePath.ARTICLES.equals(subtree) && !ArchivePath.IMAGES.equals(subtree)) {
            return List.of();
        }
        // subtree + five date levels = 6 segments, and that last one is the
        // folder holding files. Anything deeper is a file path, not a folder.
        if (segments.length > 6) {
            return List.of();
        }
        int[] levels = levels(segments);
        if (levels == null) {
            return List.of();
        }
        return levels.length == 5
                ? leaf(subtree, levels)
                : partitionFolders(clean, levels);
    }

    /**
     * The next level of date folders, from the range the archive actually
     * holds.
     *
     * <p>Clamped to that range so a fresh archive does not show sixteen years
     * of empty folders — feed windows reach back years, but what the tree is
     * organised by is when we collected, and that range is short and known.
     */
    private List<OdeFileEntry> partitionFolders(String path, int[] levels) {
        Optional<Instant> oldest = articleService.oldestArticleAt();
        Optional<Instant> newest = articleService.newestArticleAt();
        if (oldest.isEmpty() || newest.isEmpty()) {
            return List.of();
        }
        ZonedDateTime from = oldest.get().atZone(ZoneOffset.UTC);
        ZonedDateTime to = newest.get().atZone(ZoneOffset.UTC);

        List<OdeFileEntry> folders = new ArrayList<>();
        switch (levels.length) {
            case 0 -> {
                for (int year = from.getYear(); year <= to.getYear(); year++) {
                    folders.add(OdeFileEntry.folder(path + "/" + year));
                }
            }
            case 1 -> {
                int year = levels[0];
                int firstMonth = year == from.getYear() ? from.getMonthValue() : 1;
                int lastMonth = year == to.getYear() ? to.getMonthValue() : 12;
                for (int month = firstMonth; month <= lastMonth; month++) {
                    folders.add(OdeFileEntry.folder(path + "/" + two(month)));
                }
            }
            case 2 -> {
                YearMonth month = YearMonth.of(levels[0], levels[1]);
                for (int day = 1; day <= month.lengthOfMonth(); day++) {
                    folders.add(OdeFileEntry.folder(path + "/" + two(day)));
                }
            }
            case 3 -> {
                for (int hour = 0; hour < 24; hour++) {
                    folders.add(OdeFileEntry.folder(path + "/" + two(hour)));
                }
            }
            case 4 -> {
                for (int minute = 0; minute < 60; minute++) {
                    folders.add(OdeFileEntry.folder(path + "/" + two(minute)));
                }
            }
            default -> {
                // Unreachable: five levels is the leaf, handled by list().
            }
        }
        return folders;
    }

    /** Every article or image collected within one minute. */
    private List<OdeFileEntry> leaf(String subtree, int[] levels) {
        Instant from;
        try {
            from = ZonedDateTime.of(levels[0], levels[1], levels[2], levels[3], levels[4],
                    0, 0, ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            // 2026/02/31 is five levels each inside its own range and still not
            // a day. Only the calendar knows, so only it can refuse this.
            return List.of();
        }
        Instant to = from.plus(1, ChronoUnit.MINUTES);

        if (ArchivePath.IMAGES.equals(subtree)) {
            List<OdeFileEntry> entries = new ArrayList<>();
            for (ImageDocument image : imageService.storedBetween(from, to)) {
                entryFor(image).ifPresent(entries::add);
            }
            return entries;
        }

        ArticleQuery query = ArticleQuery.builder().since(from).until(to).build();
        // Complete for the folder, not a page: what a listing omits, the
        // reader deletes. The minute partition is what keeps this bounded.
        int size = (int) Math.min(articleService.count(query), 10_000);
        return entriesFor(articleService.search(query, 0, Math.max(size, 1)));
    }

    // ─── open ───

    @Override
    public InputStream open(String path) {
        ArchivePath.Ref ref = ArchivePath.parse(normalise(path))
                .orElseThrow(() -> new IllegalArgumentException("not a path in this mount: " + path));

        return switch (ref.kind()) {
            case ARTICLE -> new ByteArrayInputStream(renderArticle(ref));
            case IMAGE -> new ByteArrayInputStream(imageBytes(ref));
        };
    }

    private byte[] renderArticle(ArchivePath.Ref ref) {
        ArticleDocument article = articleService.findById(ref.id())
                .filter(found -> ref.matches(found.getFirstSeenAt()))
                .orElseThrow(() -> new IllegalArgumentException("no such article: " + ref.id()));
        ArticleContentDocument content = articleService.findContent(ref.id()).orElse(null);
        return ArticleMarkdown.renderBytes(article, content);
    }

    private byte[] imageBytes(ArchivePath.Ref ref) {
        ImageDocument image = imageService.loadById(ref.id())
                .filter(found -> ref.matches(found.getFirstSeenAt()))
                .orElseThrow(() -> new IllegalArgumentException("no such image: " + ref.id()));
        byte[] data = image.getData();
        if (data == null) {
            throw new IllegalStateException("image " + ref.id() + " is stored without bytes");
        }
        return data;
    }

    // ─── search ───

    /**
     * Delegated search, over the same index the research surface uses.
     *
     * <p>Answering it here rather than letting the reader walk the tree is the
     * whole reason {@code canSearch} is true: this archive has a text index,
     * and a tree walk over half a million files to find a phrase is the
     * alternative.
     *
     * <p>Ordered by relevance and not by date, which is the difference between a
     * search and a listing: there is one page and no cursor, so the best match
     * has to be on it. Bodies are searched too — a mount holds the whole
     * article, so a phrase in the fifth paragraph is inside the file the caller
     * is looking for.
     */
    @Override
    public List<OdeFileEntry> search(String query, int limit) {
        if (StringUtils.isBlank(query)) {
            return List.of();
        }
        ArticleQuery filter = ArticleQuery.builder().text(query.strip()).build();
        List<ArticleDocument> hits =
                articleService.searchByRelevance(filter, null, Math.max(limit, 1), true);
        return entriesFor(hits);
    }

    // ─── entries ───

    /**
     * Entries for a whole page of articles, with their bodies read in one query.
     *
     * <p>Which is the difference that matters at this size: the archive's
     * busiest minute so far holds 2,009 articles, and a listing of it asking
     * per article is 2,009 round trips to produce metadata rows. The bodies are
     * needed either way — the size and the etag are properties of the rendering,
     * not of the article — so what is saved is the waiting, not the reading.
     */
    private List<OdeFileEntry> entriesFor(List<ArticleDocument> articles) {
        List<String> ids = new ArrayList<>(articles.size());
        for (ArticleDocument article : articles) {
            ids.add(StringUtils.defaultString(article.getId()));
        }
        Map<String, ArticleContentDocument> bodies = articleService.findContent(ids);

        List<OdeFileEntry> entries = new ArrayList<>(articles.size());
        for (ArticleDocument article : articles) {
            entries.add(entryFor(article,
                    bodies.get(StringUtils.defaultString(article.getId()))));
        }
        return entries;
    }

    private OdeFileEntry entryFor(ArticleDocument article) {
        String id = StringUtils.defaultString(article.getId());
        return entryFor(article, articleService.findContent(id).orElse(null));
    }

    private OdeFileEntry entryFor(ArticleDocument article,
            @Nullable ArticleContentDocument content) {
        String id = StringUtils.defaultString(article.getId());
        byte[] rendered = ArticleMarkdown.renderBytes(article, content);
        return new OdeFileEntry(
                ArchivePath.ofArticle(id, article.getFirstSeenAt()),
                false,
                rendered.length,
                ArticleMarkdown.MIME,
                // A hash of the rendering, because the rendering is what this
                // file is. The alternative was a timestamp, and there is no
                // single one that covers every change: a body arrives, a
                // source is added, a translation lands, and they update
                // different fields. The bytes are in hand anyway — the size
                // came from them — so the exact answer is the cheap one.
                etag(rendered),
                changedAt(article).toEpochMilli(),
                StringUtils.trimToNull(article.getTitle()));
    }

    /**
     * The most recent thing known to have changed this article.
     *
     * <p>A best effort by construction, which is why the etag does not rely on
     * it: it is here because the reader displays a modification time, and a
     * file dated to its collection while its body arrived yesterday reads as
     * wrong.
     */
    private static Instant changedAt(ArticleDocument article) {
        Instant latest = article.getFirstSeenAt();
        if (article.getLastSourceAddedAt().isAfter(latest)) {
            latest = article.getLastSourceAddedAt();
        }
        Instant fetched = article.getContentFetchedAt();
        if (fetched != null && fetched.isAfter(latest)) {
            latest = fetched;
        }
        return latest;
    }

    private Optional<OdeFileEntry> entryFor(ImageDocument image) {
        return ArchivePath.ofImage(image.getId(), image.getFirstSeenAt(), image.getMime())
                .map(path -> new OdeFileEntry(
                        path,
                        false,
                        image.getSize(),
                        image.getMime(),
                        // The content hash, which for an image is exact and
                        // already computed: the bytes never change under a
                        // path, so this also says "not modified" forever.
                        image.getContentHash(),
                        image.getStoredAt() == null ? null : image.getStoredAt().toEpochMilli(),
                        null));
    }

    /**
     * A quoted, truncated SHA-256 of the content.
     *
     * <p>Sixteen hex characters — 64 bits. This answers "has it changed", not
     * "is it authentic", and a collision costs one stale rendering in a
     * reader's cache until the next change.
     */
    private static String etag(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // ─── paths ───

    private static String normalise(@Nullable String path) {
        return StringUtils.strip(StringUtils.defaultString(path), "/");
    }

    /** A folder is a prefix of the layout: subtree plus up to five date levels. */
    private static boolean isFolder(String path) {
        String[] segments = path.split("/");
        if (segments.length > 6) {
            return false;
        }
        boolean known = ArchivePath.ARTICLES.equals(segments[0])
                || ArchivePath.IMAGES.equals(segments[0]);
        return known && levels(segments) != null;
    }

    /**
     * The date levels of a path, or {@code null} when it is not one.
     *
     * <p>The one place a path segment becomes a number, because "digits" and "a
     * date level" are not the same thing and the difference is what a caller
     * types: {@code article/2026/13} and {@code article/99999999999} are both
     * numeric, and parsing them raw is a {@code DateTimeException} and a
     * {@code NumberFormatException} on a path somebody guessed at.
     *
     * <p>Each level is also required to be spelled the way this tree writes
     * it — four digits for the year, two below it. Otherwise {@code /8} would
     * be a second, working address for the folder {@code /08} already names,
     * and a mount whose paths have two spellings has broken the one promise the
     * contract rests on.
     *
     * @return one entry per level, in path order, or {@code null}
     */
    private static int @Nullable [] levels(String[] segments) {
        int[] levels = new int[segments.length - 1];
        for (int i = 1; i < segments.length; i++) {
            Integer level = level(segments[i], LEVEL_WIDTHS[i - 1], LEVEL_BOUNDS[i - 1]);
            if (level == null) {
                return null;
            }
            levels[i - 1] = level;
        }
        return levels;
    }

    /** Digits per level: a four-digit year, then two each for month to minute. */
    private static final int[] LEVEL_WIDTHS = {4, 2, 2, 2, 2};

    /**
     * Inclusive {@code {min, max}} per level. The day stops at 31 because a
     * month is not known at that point in the path; the calendar settles
     * February in {@link #leaf}.
     */
    private static final int[][] LEVEL_BOUNDS = {{1000, 9999}, {1, 12}, {1, 31}, {0, 23}, {0, 59}};

    private static @Nullable Integer level(String segment, int width, int[] bounds) {
        if (segment.length() != width || !StringUtils.isNumeric(segment)) {
            return null;
        }
        int value = Integer.parseInt(segment);
        return value < bounds[0] || value > bounds[1] ? null : value;
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
