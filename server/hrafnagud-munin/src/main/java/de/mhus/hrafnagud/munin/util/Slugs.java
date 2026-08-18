package de.mhus.hrafnagud.munin.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * Derives the technical {@code name} of a source or source list from its
 * URL.
 *
 * <p>Names are derived from the <em>URL</em>, never from the title, and
 * that is the whole point: a publisher renaming its feed from "Politik" to
 * "Politik &amp; Wirtschaft" must not produce a second source and a second
 * copy of its archive. The host makes the name legible to an operator, the
 * hash suffix makes it unique when one host serves fifty feeds.
 *
 * <p>Hosts in non-Latin scripts slug to nothing, so the hash carries the
 * whole name in that case. That is acceptable — the name is a key, and the
 * human-facing label is {@code title}.
 */
public final class Slugs {

    private static final int HASH_LENGTH = 6;
    private static final int MAX_HOST_PART = 40;
    private static final int MAX_LENGTH = 128;

    private Slugs() {
    }

    /**
     * Reduces arbitrary text to {@code [a-z0-9-]}, folding accents into
     * their base letters. Returns an empty string when nothing survives,
     * which callers must handle.
     */
    public static String slugify(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if (trimmed.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFKD);
        String ascii = decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String slug = ascii.replaceAll("[^a-z0-9]+", "-");
        slug = StringUtils.strip(slug, "-");
        return slug.length() > MAX_LENGTH ? slug.substring(0, MAX_LENGTH) : slug;
    }

    /**
     * Builds a stable source name for a normalised URL, e.g.
     * {@code spiegel-de-1f3a9c}.
     */
    public static String sourceName(String normalizedUrl) {
        String hostPart = slugify(hostOf(normalizedUrl));
        if (hostPart.length() > MAX_HOST_PART) {
            hostPart = StringUtils.strip(hostPart.substring(0, MAX_HOST_PART), "-");
        }
        String hash = Hashes.shortHash(normalizedUrl, HASH_LENGTH);
        return hostPart.isEmpty() ? "src-" + hash : hostPart + "-" + hash;
    }

    /** Host of a URL, or an empty string when it has none. */
    public static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
