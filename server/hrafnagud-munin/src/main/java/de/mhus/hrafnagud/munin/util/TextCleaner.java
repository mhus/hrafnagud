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

    /** Counts whitespace-separated words. */
    public static int wordCount(@Nullable String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
