package de.mhus.hrafnagud.munin.util;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Reduces a URL to the canonical form used as an article's identity.
 *
 * <p>This is the single most consequential piece of logic in the ingest
 * path. The same wire-service report reaches us from dozens of feeds, each
 * decorating the link with its own campaign parameters, its own AMP variant
 * and its own idea of trailing slashes. Without normalisation the archive
 * fills with near-identical rows and every downstream query returns the same
 * story twenty times; with normalisation that is too aggressive, genuinely
 * different articles collapse into one and the content is lost.
 *
 * <p>The rules therefore lean conservative — every transformation below is
 * one where the two forms are known to address the same document:
 *
 * <ol>
 *   <li>scheme and host lowercased, host punycoded, a leading {@code www.}
 *       dropped, default ports dropped, credentials dropped;</li>
 *   <li>fragment dropped — it addresses a position within a document, not a
 *       document;</li>
 *   <li>known tracking parameters dropped, by exact name and by prefix
 *       family ({@code utm_}, {@code at_}, {@code pk_}, …);</li>
 *   <li>AMP markers dropped in their two common spellings, a trailing
 *       {@code /amp} path segment and an {@code amp}-valued output
 *       parameter;</li>
 *   <li>remaining query parameters sorted, so two feeds that emit the same
 *       pair in a different order agree;</li>
 *   <li>a trailing slash dropped from non-root paths.</li>
 * </ol>
 *
 * <p>Deliberately <em>not</em> done: dropping an {@code m.} host prefix or
 * an {@code amp.} subdomain, and following redirects. The first two are
 * frequently distinct hosts with distinct content; the third is IO and
 * belongs to the fetcher, which records the post-redirect URL separately.
 */
public final class UrlNormalizer {

    /**
     * Parameters dropped by exact name. Campaign trackers, social-referrer
     * markers, and click identifiers — none of which change which document
     * the server returns.
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "twclid", "igshid",
            "mc_cid", "mc_eid", "yclid", "wickedid", "s_cid", "ncid", "cmpid",
            "cmp", "ref", "ref_src", "referrer", "source", "src",
            "__twitter_impression", "guccounter", "guce_referrer",
            "guce_referrer_sig", "spm", "scmp_source", "sh", "smid", "smtyp",
            "partner", "sr_share", "xtor", "ito", "cid", "ocid", "ff_source",
            "ff_medium", "ff_campaign", "wtmc", "wt_mc", "wt_zmc", "gaa_at",
            "gaa_n", "gaa_ts", "gaa_sig");

    /**
     * Parameter families dropped by prefix: Google Analytics ({@code utm_}),
     * AT Internet ({@code at_}), Piwik/Matomo ({@code pk_}, {@code mtm_},
     * {@code piwik_}), HubSpot ({@code hsa_}), Nielsen ({@code ns_}) and
     * Vero ({@code vero_}).
     */
    private static final List<String> TRACKING_PREFIXES = List.of(
            "utm_", "at_", "pk_", "mtm_", "piwik_", "hsa_", "ns_", "vero_",
            "_hs", "ir_", "epik");

    /** Parameters whose only job is to select the AMP rendering. */
    private static final Set<String> AMP_PARAMS = Set.of(
            "amp", "outputtype", "output", "amp_js_v", "usqp", "amp_gsa");

    /** Values of {@link #AMP_PARAMS} that mean "AMP variant". */
    private static final Set<String> AMP_VALUES = Set.of("amp", "1", "true", "amp_type_a");

    /**
     * ASCII characters that occur in published links but are not legal in a
     * URI, so {@link URI} would reject the whole thing over one of them.
     */
    private static final java.util.regex.Pattern ILLEGAL_ASCII =
            java.util.regex.Pattern.compile("[\\\\^`{|}\\[\\]\"<> ]");

    private static final int MAX_LENGTH = 2000;

    private UrlNormalizer() {
    }

    /**
     * Normalises {@code raw}, or returns empty when it is not a usable
     * absolute http(s) URL.
     *
     * <p>Returning empty rather than throwing is deliberate: unusable links
     * are an everyday occurrence in feeds ({@code javascript:} handlers,
     * bare {@code mailto:}, relative paths, empty elements) and the caller's
     * response is always the same — count it as invalid and move on.
     */
    public static Optional<String> normalize(String raw) {
        String cleaned = preClean(raw);
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = new URI(cleaned);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return Optional.empty();
        }

        String host = normalizeHost(uri.getHost());
        if (host.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder out = new StringBuilder(cleaned.length());
        out.append(scheme).append("://").append(host);

        int port = uri.getPort();
        if (port > 0 && !isDefaultPort(scheme, port)) {
            out.append(':').append(port);
        }

        out.append(normalizePath(uri.getRawPath()));

        String query = normalizeQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            out.append('?').append(query);
        }

        String result = out.toString();
        return result.length() > MAX_LENGTH ? Optional.empty() : Optional.of(result);
    }

    /**
     * Normalises and returns the input unchanged (trimmed) when that is not
     * possible — for fields where keeping an odd value beats losing it, such
     * as a source's declared site URL.
     */
    public static String normalizeOrRaw(String raw) {
        return normalize(raw).orElseGet(() -> StringUtils.trimToEmpty(raw));
    }

    /**
     * Repairs the two malformations that appear often enough in feeds to be
     * worth handling — whitespace inside the URL and a scheme-relative or
     * scheme-less link — and rejects anything else by returning empty.
     */
    private static String preClean(String raw) {
        String value = StringUtils.trimToEmpty(raw);
        if (value.isEmpty()) {
            return "";
        }
        // Control characters and stray whitespace: feeds wrap long links
        // across lines and the parser hands them to us with the newline.
        value = value.replaceAll("[\\p{Cntrl}\\s]", "");
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("//")) {
            value = "https:" + value;
        } else if (!value.contains("://")) {
            // A bare "example.com/path". Only treat it as a host when it
            // actually looks like one — this must not swallow "mailto:x@y".
            if (value.contains(":") || !value.contains(".")) {
                return "";
            }
            value = "https://" + value;
        }
        return toAsciiUri(value);
    }

    /**
     * Converts an IRI to a URI: punycode in the host, percent-encoding in
     * the rest.
     *
     * <p>Required because {@link URI} rejects non-ASCII outright —
     * {@code URI.getHost()} returns {@code null} for a Unicode host, and the
     * constructor throws on a Unicode path. Without this step every feed on
     * an internationalised domain, and every article whose path was not
     * pre-encoded by its publisher, would be dropped as unparseable. For a
     * collector that is explicitly worldwide that is not an edge case.
     */
    private static String toAsciiUri(String value) {
        int authorityStart = value.indexOf("://") + 3;
        int authorityEnd = value.length();
        for (int i = authorityStart; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = punycode(value.substring(authorityStart, authorityEnd));
        String rest = percentEncodeIllegal(value.substring(authorityEnd));
        return value.substring(0, authorityStart) + authority + rest;
    }

    /** Punycodes the host part of an authority, leaving user info and port. */
    private static String punycode(String authority) {
        if (isAscii(authority)) {
            return authority;
        }
        int at = authority.lastIndexOf('@');
        String userInfo = at < 0 ? "" : authority.substring(0, at + 1);
        String hostPort = at < 0 ? authority : authority.substring(at + 1);

        // A bracketed IPv6 literal is always ASCII, so it never reaches here.
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        String port = colon < 0 ? "" : hostPort.substring(colon);
        try {
            return userInfo + IDN.toASCII(host) + port;
        } catch (IllegalArgumentException e) {
            return authority;
        }
    }

    /**
     * Percent-encodes bytes that {@link URI} refuses: everything non-ASCII,
     * plus the handful of ASCII characters that appear in real links but are
     * not legal in a URI.
     */
    private static String percentEncodeIllegal(String value) {
        if (isAscii(value) && !ILLEGAL_ASCII.matcher(value).find()) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String chunk = value.substring(i, i + charCount);
            if (codePoint < 128 && !ILLEGAL_ASCII.matcher(chunk).matches()) {
                out.append(chunk);
            } else {
                for (byte b : chunk.getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
            i += charCount;
        }
        return out.toString();
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHost(String rawHost) {
        String host = StringUtils.trimToEmpty(rawHost).toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return "";
        }
        host = StringUtils.removeEnd(host, ".");
        if (host.startsWith("www.") && host.length() > 4) {
            host = host.substring(4);
        }
        try {
            // Worldwide sources means internationalised hosts. Punycode is
            // the form two feeds spelling the same host differently agree on.
            host = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            // Malformed IDN — keep the lowercased form rather than dropping
            // the URL entirely.
        }
        return host;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    private static String normalizePath(String rawPath) {
        String path = StringUtils.defaultString(rawPath);
        if (path.isEmpty()) {
            return "/";
        }
        path = path.replaceAll("/{2,}", "/");
        path = stripAmpSegment(path);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /** Drops a trailing {@code /amp} or {@code /amp/} segment. */
    private static String stripAmpSegment(String path) {
        String candidate = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (candidate.length() > 4 && candidate.toLowerCase(Locale.ROOT).endsWith("/amp")) {
            return candidate.substring(0, candidate.length() - 4);
        }
        return path;
    }

    /**
     * Drops tracking and AMP parameters and sorts the rest.
     *
     * <p>Works on the raw (still percent-encoded) query so that values are
     * never decoded and re-encoded — a round trip through a decoder is how
     * {@code %2B} silently becomes a space and two equivalent URLs stop
     * matching.
     */
    private static String normalizeQuery(String rawQuery) {
        if (StringUtils.isEmpty(rawQuery)) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : pair.substring(eq + 1).toLowerCase(Locale.ROOT);
            if (isTracking(name) || isAmp(name, value)) {
                continue;
            }
            kept.add(pair);
        }
        // Sorting makes ?a=1&b=2 and ?b=2&a=1 the same article. Repeated
        // keys keep their relative order because the sort is stable.
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }

    private static boolean isTracking(String name) {
        if (TRACKING_PARAMS.contains(name)) {
            return true;
        }
        for (String prefix : TRACKING_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAmp(String name, String value) {
        return AMP_PARAMS.contains(name) && AMP_VALUES.contains(value);
    }
}
