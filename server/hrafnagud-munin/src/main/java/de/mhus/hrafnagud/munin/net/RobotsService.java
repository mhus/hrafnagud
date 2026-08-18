package de.mhus.hrafnagud.munin.net;

import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Decides whether a URL may be fetched, caching one {@code robots.txt} per
 * origin.
 *
 * <p>Only the article fetcher consults this. Feeds are excluded on purpose:
 * a feed is a document published for the express purpose of being polled by
 * software, and treating it as crawlable-only-if-permitted would be a
 * misreading of what the publisher offered. Fetching the article page
 * behind it is a different act, and that one is governed here.
 *
 * <p>A missing or unreadable {@code robots.txt} means allowed — that is
 * what the protocol says, and it is also what publishers assume. A 4xx that
 * is specifically {@code 401} or {@code 403} on the {@code robots.txt}
 * itself means the opposite: the host is telling us we may not even read
 * the rules, and we stay out.
 */
@Service
@Slf4j
public class RobotsService {

    /** Origins cached. Bounded because a worldwide registry has many. */
    private static final int MAX_CACHED_ORIGINS = 20_000;

    private final HttpFetcher fetcher;
    private final MuninProperties.Content config;
    private final String agentToken;

    private final Map<String, CachedRules> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedRules> eldest) {
                    return size() > MAX_CACHED_ORIGINS;
                }
            });

    private record CachedRules(RobotsRules rules, Instant fetchedAt) {
    }

    public RobotsService(HttpFetcher fetcher, MuninProperties properties) {
        this.fetcher = fetcher;
        this.config = properties.getContent();
        this.agentToken = productToken(properties.getHttp().getUserAgent());
    }

    /**
     * Whether {@code url} may be fetched. Returns {@code true} immediately
     * when robots checking is disabled or the URL is unparseable — the
     * caller will fail on the URL anyway, and denying here would report the
     * wrong reason.
     */
    public boolean isAllowed(String url, Instant now) {
        if (!config.isRespectRobots()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return true;
        }
        if (uri.getHost() == null) {
            return true;
        }
        String origin = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        RobotsRules rules = rulesFor(origin, now);

        String path = StringUtils.defaultIfEmpty(uri.getRawPath(), "/");
        String query = uri.getRawQuery();
        return rules.isAllowed(query == null ? path : path + "?" + query);
    }

    private RobotsRules rulesFor(String origin, Instant now) {
        CachedRules cached = cache.get(origin);
        if (cached != null && cached.fetchedAt().plus(config.getRobotsCacheTtl()).isAfter(now)) {
            return cached.rules();
        }
        RobotsRules rules = load(origin);
        cache.put(origin, new CachedRules(rules, now));
        return rules;
    }

    private RobotsRules load(String origin) {
        HttpFetchResult result = fetcher.get(origin + "/robots.txt");
        if (result.isSuccess()) {
            try {
                return RobotsRules.parse(result.bodyAsText(), agentToken);
            } catch (RuntimeException e) {
                log.trace("RobotsService: unparseable robots.txt at {}: {}", origin, e.toString());
                return RobotsRules.ALLOW_ALL;
            }
        }
        if (result.getStatus() == 401 || result.getStatus() == 403) {
            log.trace("RobotsService: {} refuses access to robots.txt — treating host as off-limits",
                    origin);
            return RobotsRules.DENY_ALL;
        }
        // 404, 5xx, timeouts: no rules published, or we could not reach
        // them. The protocol treats both as unrestricted.
        return RobotsRules.ALLOW_ALL;
    }

    /**
     * Extracts the product token from a user-agent string —
     * {@code "Hrafnagud/0.1 (+https://…)"} yields {@code "hrafnagud"} — so
     * that a publisher naming us in {@code robots.txt} is matched.
     */
    static String productToken(String userAgent) {
        String head = StringUtils.substringBefore(StringUtils.trimToEmpty(userAgent), " ");
        String token = StringUtils.substringBefore(head, "/");
        return token.isEmpty() ? "*" : token.toLowerCase(Locale.ROOT);
    }

    /** Cached origins — surfaced for diagnostics. */
    public int cachedOrigins() {
        return cache.size();
    }
}
