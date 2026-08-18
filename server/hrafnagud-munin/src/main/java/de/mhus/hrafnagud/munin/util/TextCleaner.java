package de.mhus.hrafnagud.munin.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jspecify.annotations.Nullable;

/** Cleanup of the strings and timestamps that arrive from feeds. */
public final class TextCleaner {

    /**
     * Anything before this is a broken date rather than an archive item. A
     * publisher emitting {@code 1970-01-01} means "we lost the date", and a
     * date-ordered view should not be anchored by it.
     */
    private static final Instant EARLIEST_PLAUSIBLE = Instant.parse("1990-01-01T00:00:00Z");

    private TextCleaner() {
    }

    /**
     * Strips markup and normalises whitespace.
     *
     * <p>Feed titles and teasers arrive as HTML fragments roughly half the
     * time — entity-encoded, wrapped in {@code <p>}, padded with tracking
     * pixels. jsoup's text extraction drops script and style payloads and
     * decodes entities, which a regex-based strip does neither of.
     */
    public static String stripHtml(@Nullable String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        String text = Jsoup.parseBodyFragment(value).text();
        return normalizeWhitespace(text);
    }

    /** Collapses all whitespace runs to single spaces and trims. */
    public static String normalizeWhitespace(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Z}\\s]+", " ").trim();
    }

    /**
     * Truncates at a word boundary and appends an ellipsis. Used to keep
     * teasers bounded — some feeds put the entire article in
     * {@code <description>}, and the teaser field is not where a 40 KB body
     * belongs.
     */
    public static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int cut = value.lastIndexOf(' ', maxChars);
        if (cut < maxChars / 2) {
            cut = maxChars;
        }
        return value.substring(0, cut).trim() + "…";
    }

    /**
     * Accepts a feed's publication date only when it is plausible.
     *
     * <p>Returns {@code null} for missing, pre-1990 and far-future dates.
     * The alternative — storing whatever the feed said — means one publisher
     * with a broken clock permanently occupies the top of any date-ordered
     * view, which is exactly why ordering uses {@code firstSeenAt} instead.
     */
    public static @Nullable Instant sanitizePublished(@Nullable Instant published, Instant now,
            long maxFutureSkewSeconds) {
        if (published == null) {
            return null;
        }
        if (published.isBefore(EARLIEST_PLAUSIBLE)) {
            return null;
        }
        if (published.isAfter(now.plus(maxFutureSkewSeconds, ChronoUnit.SECONDS))) {
            return null;
        }
        return published;
    }

    /**
     * Reduces a language tag to its lowercase primary subtag
     * ({@code de-DE} → {@code de}), or {@code null} when it is not a
     * plausible tag.
     *
     * <p>Regional variants are dropped on purpose: nothing downstream
     * distinguishes Austrian from German German, while feeds are wildly
     * inconsistent about whether they emit {@code de}, {@code de-DE},
     * {@code de_AT} or {@code german}.
     */
    public static @Nullable String normalizeLanguage(@Nullable String tag) {
        if (StringUtils.isBlank(tag)) {
            return null;
        }
        String value = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        if (dash > 0) {
            value = value.substring(0, dash);
        }
        if (!value.matches("[a-z]{2,3}")) {
            return null;
        }
        return value;
    }

    /**
     * Characters from scripts that do not separate words with spaces:
     * Han, Hiragana, Katakana, Hangul and Thai.
     */
    private static final java.util.regex.Pattern UNSPACED_SCRIPT =
            java.util.regex.Pattern.compile(
                    "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}]");

    /**
     * Average characters per word in the unspaced scripts. Two is the
     * conventional figure for Japanese and Chinese and is close enough for
     * Korean and Thai — the number is used for length thresholds, not for
     * linguistics.
     */
    private static final int CHARS_PER_UNSPACED_WORD = 2;

    /**
     * Approximate word count, usable across writing systems.
     *
     * <p>Counting whitespace-separated tokens is the obvious implementation
     * and is wrong for a large part of the world: Japanese, Chinese, Korean
     * and Thai do not put spaces between words, so a full article scores a
     * handful of "words". Every length threshold built on that then
     * misfires in the same direction — a complete Japanese article looks
     * like a stub, gets rejected as too short, and the archive quietly ends
     * up holding no CJK bodies at all.
     *
     * <p>So characters from unspaced scripts are counted separately and
     * divided by an average word length, and the remainder is tokenised as
     * usual. Mixed text (a Japanese article quoting an English name) is
     * handled by both halves at once.
     */
    public static int wordCount(@Nullable String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        String value = text.trim();

        int unspacedChars = 0;
        java.util.regex.Matcher matcher = UNSPACED_SCRIPT.matcher(value);
        while (matcher.find()) {
            unspacedChars++;
        }

        String spaced = UNSPACED_SCRIPT.matcher(value).replaceAll(" ").trim();
        int spacedWords = spaced.isEmpty() ? 0 : spaced.split("\\s+").length;

        return spacedWords + unspacedChars / CHARS_PER_UNSPACED_WORD;
    }
}
