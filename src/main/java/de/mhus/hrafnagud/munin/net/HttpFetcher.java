package de.mhus.hrafnagud.munin.net;

import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.Settings;
import de.mhus.hrafnagud.munin.util.Slugs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one place Munin talks HTTP.
 *
 * <p>Every request is paced by {@link HostRateLimiter}, carries the
 * configured user agent, sends conditional headers when the caller has
 * validators, and is cut off at the configured body size. Bodies are read
 * through a bounded stream rather than buffered whole, so a URL that turns
 * out to point at a video does not become an out-of-memory error.
 *
 * <p>Failures are returned, not thrown. A crawl over thousands of hosts
 * fails constantly and in every possible way; treating that as exceptional
 * would mean wrapping every call site in the same catch block.
 *
 * <p>Being the only exit point is also what makes an outbound proxy a
 * single setting rather than a change in four workers: feeds, source lists,
 * article pages and {@code robots.txt} all leave through here.
 */
@Component
@Slf4j
public class HttpFetcher {

    private final HttpClient client;
    private final HostRateLimiter rateLimiter;

    /**
     * Per-request behaviour, read at the moment of the request.
     *
     * <p>What the client is built from — the proxy and the connect timeout —
     * cannot be among these: the client is one shared object, created here and
     * used for the rest of the process. Changing either means a restart, which
     * is why they stay properties. See {@code specs/settings.md} §3.
     */
    private final Settings.Http config;

    public HttpFetcher(MuninProperties properties, Settings settings) {
        this.config = settings.getHttp();
        this.rateLimiter = new HostRateLimiter(() -> config.minHostInterval().value());

        MuninProperties.Http startup = properties.getHttp();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(startup.getConnectTimeout())
                // NORMAL follows redirects but refuses an HTTPS→HTTP
                // downgrade, which is the behaviour we want and would
                // otherwise have to implement by hand.
                .followRedirects(HttpClient.Redirect.NORMAL);

        MuninProperties.Proxy proxy = startup.getProxy();
        proxySelector(proxy).ifPresentOrElse(
                selector -> {
                    builder.proxy(selector);
                    log.info("Outbound HTTP goes through proxy {}:{}",
                            proxy.getHost(), proxy.getPort());
                },
                () -> log.info("Outbound HTTP goes out directly (no proxy configured)"));

        this.client = builder.build();
    }

    /**
     * Builds the proxy selector, or empty when no proxy is configured.
     *
     * <p>Extracted and static so the three cases — unset, valid, and
     * half-configured — can be tested without constructing a client.
     *
     * @throws IllegalArgumentException when a host is given without a usable
     *         port; see {@link MuninProperties.Proxy} for why that fails
     *         loudly instead of falling back to a direct connection
     */
    static Optional<ProxySelector> proxySelector(MuninProperties.Proxy proxy) {
        if (!proxy.isConfigured()) {
            return Optional.empty();
        }
        int port = proxy.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "munin.http.proxy.host is set to '" + proxy.getHost()
                            + "' but munin.http.proxy.port is " + port
                            + " — a proxy needs a port in 1..65535");
        }
        return Optional.of(ProxySelector.of(
                new InetSocketAddress(proxy.getHost().trim(), port)));
    }

    /**
     * The target of the one redirect {@link HttpClient.Redirect#NORMAL} will
     * not follow: a hop from HTTPS down to plain HTTP.
     *
     * <p>Refusing the downgrade is right — it is why NORMAL is chosen over
     * ALWAYS — but on a real crawl it strands feeds that are not actually
     * broken. The shape is common enough to name: a site redirects
     * {@code https://host/feed} to {@code http://www.host/feed/}, whose
     * server then redirects straight back up to HTTPS. Every browser and
     * curl walk through it without noticing. We stop at the middle hop and
     * book a permanently failing source.
     *
     * <p>So rather than follow the downgrade, this <b>skips</b> it: the
     * target is retried with the scheme put back to HTTPS. If the publisher
     * really is HTTPS-capable — the case this exists for — that request is
     * the one their own redirect would have arrived at anyway. If they are
     * genuinely HTTP-only, it fails, and the source stays failing with a
     * message that says so. What this never does is fetch a feed over
     * plaintext because a redirect asked it to.
     *
     * <p>An explicit {@code :80} is dropped along with the scheme; keeping it
     * would ask for TLS on the cleartext port.
     *
     * @param from     URI the response came from — the Location header is
     *                 relative to it, which after followed hops is not the
     *                 URL the caller passed in
     * @param status   response status; anything outside 3xx yields empty
     * @param location {@code Location} header, if there was one
     * @return the HTTPS URL to try instead, or empty when there is nothing
     *         here to repair
     */
    static Optional<URI> repairTarget(URI from, int status, @Nullable String location) {
        if (!isRedirect(status) || StringUtils.isBlank(location)) {
            return Optional.empty();
        }
        URI target;
        try {
            target = from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean downgrade = "https".equalsIgnoreCase(from.getScheme())
                && "http".equalsIgnoreCase(target.getScheme());
        if (!downgrade || StringUtils.isBlank(target.getHost())) {
            // Every other redirect the client either followed already or
            // cannot be helped with here.
            return Optional.empty();
        }

        StringBuilder upgraded = new StringBuilder("https://");
        if (target.getRawUserInfo() != null) {
            upgraded.append(target.getRawUserInfo()).append('@');
        }
        upgraded.append(target.getHost());
        if (target.getPort() != -1 && target.getPort() != 80) {
            upgraded.append(':').append(target.getPort());
        }
        upgraded.append(StringUtils.defaultString(target.getRawPath()));
        if (target.getRawQuery() != null) {
            upgraded.append('?').append(target.getRawQuery());
        }
        try {
            return Optional.of(URI.create(upgraded.toString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code true} for the redirects that mean "and stop asking the old URL". */
    static boolean isPermanentRedirect(int status) {
        return status == 301 || status == 308;
    }

    /**
     * {@code true} for a 3xx that actually points somewhere else.
     *
     * <p>304 lives in the same hundred and is the opposite of a redirect: it
     * is the cheapest successful poll there is. Treating it as one would
     * annotate every unchanged feed with a complaint about a missing
     * {@code Location} header — and callers that gate on
     * {@link HttpFetchResult#isSuccess()}, which 304 is not, would show it.
     */
    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400 && status != 304;
    }

    /** Unconditional GET. */
    public HttpFetchResult get(String url) {
        return get(url, null, null);
    }

    /**
     * Conditional GET.
     *
     * <p>Passing the validators from the previous fetch is what turns a
     * poll of an unchanged feed into a 304 with no body — the difference
     * between a few hundred bytes and a few hundred kilobytes per poll,
     * multiplied by every source on every interval.
     *
     * @param etag         value of the previous response's {@code ETag}
     * @param lastModified value of the previous response's {@code Last-Modified}
     */
    public HttpFetchResult get(String url, @Nullable String etag, @Nullable String lastModified) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return failure(url, "malformed url: " + e.getMessage());
        }

        try {
            rateLimiter.acquire(Slugs.hostOf(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(url, "interrupted while waiting for host slot");
        }

        try {
            HttpResponse<InputStream> response = client.send(
                    request(uri, etag, lastModified), HttpResponse.BodyHandlers.ofInputStream());

            Optional<URI> repair = repairTarget(
                    response.uri() == null ? uri : response.uri(),
                    response.statusCode(), header(response, "location"));
            if (repair.isEmpty()) {
                return toResult(url, response, null);
            }
            return followRepaired(url, response, repair.get(), etag, lastModified);
        } catch (IOException e) {
            log.trace("HttpFetcher: {} failed: {}", url, e.toString());
            return failure(url, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(url, "interrupted");
        }
    }

    /**
     * Retries the skipped-over target once, and reports the result as if it
     * had come from the URL the caller asked for.
     *
     * <p>Once, not in a loop: the client itself follows whatever chain
     * begins at the repaired URL, so the only thing a second manual hop
     * could add is a second downgrade — a site bouncing between schemes,
     * which is a configuration nobody can crawl their way out of. That one
     * is reported, not chased.
     */
    private HttpFetchResult followRepaired(String requestedUrl,
            HttpResponse<InputStream> redirect, URI target,
            @Nullable String etag, @Nullable String lastModified)
            throws IOException, InterruptedException {

        boolean permanent = isPermanentRedirect(redirect.statusCode());
        redirect.body().close();

        log.debug("HttpFetcher: {} redirects to {} — skipping the http hop, trying {}",
                requestedUrl, header(redirect, "location"), target);
        rateLimiter.acquire(Slugs.hostOf(target.toString()));

        HttpResponse<InputStream> response = client.send(
                request(target, etag, lastModified), HttpResponse.BodyHandlers.ofInputStream());
        // Worth remembering only when the publisher said "permanently" AND
        // the new location actually answers. A 404 there means the redirect
        // is as broken as the original URL.
        boolean usable = response.statusCode() == 304
                || (response.statusCode() >= 200 && response.statusCode() < 300);
        return toResult(requestedUrl, response,
                permanent && usable ? target.toString() : null);
    }

    private HttpRequest request(URI uri, @Nullable String etag, @Nullable String lastModified) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(config.readTimeout().value())
                .header("User-Agent", config.userAgent().value())
                .header("Accept-Encoding", "identity");
        if (StringUtils.isNotBlank(etag)) {
            request.header("If-None-Match", etag);
        }
        if (StringUtils.isNotBlank(lastModified)) {
            request.header("If-Modified-Since", lastModified);
        }
        return request.build();
    }

    private HttpFetchResult toResult(String requestedUrl, HttpResponse<InputStream> response,
            @Nullable String movedTo) throws IOException {
        byte[] body = response.statusCode() == 304
                ? new byte[0]
                : readBounded(response.body(), config.maxBodyBytes().value());

        String contentTypeHeader = header(response, "content-type");
        return HttpFetchResult.builder()
                .status(response.statusCode())
                .body(body)
                .contentType(baseContentType(contentTypeHeader))
                .headerCharset(charsetOf(contentTypeHeader))
                .etag(header(response, "etag"))
                .lastModified(header(response, "last-modified"))
                .finalUrl(response.uri() == null ? requestedUrl : response.uri().toString())
                .movedTo(movedTo)
                .error(unfollowedRedirect(response))
                .build();
    }

    /**
     * Says out loud that a redirect was refused rather than failed.
     *
     * <p>A bare "HTTP 301" in a source's {@code lastError} reads as a
     * publisher problem and sends whoever looks at it to the wrong end of
     * the connection.
     */
    private static @Nullable String unfollowedRedirect(HttpResponse<?> response) {
        int status = response.statusCode();
        if (!isRedirect(status)) {
            return null;
        }
        String location = header(response, "location");
        return StringUtils.isBlank(location)
                ? "HTTP " + status + " without a Location header"
                : "HTTP " + status + " — redirect to " + location + " not followed";
    }

    /**
     * Reads at most {@code limit} bytes and closes the stream.
     *
     * <p>Truncating rather than failing is intentional: a feed that exceeds
     * the cap is usually a feed with a decade of history in it, and the
     * first megabyte holds the recent entries we actually want. A truncated
     * XML document fails to parse, which the caller reports as a parse
     * error — an honest outcome either way.
     */
    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                long remaining = limit - total;
                if (remaining <= 0) {
                    break;
                }
                int usable = (int) Math.min(read, remaining);
                out.write(buffer, 0, usable);
                total += usable;
            }
            return out.toByteArray();
        }
    }

    private static @Nullable String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static @Nullable String baseContentType(@Nullable String header) {
        if (StringUtils.isBlank(header)) {
            return null;
        }
        int semicolon = header.indexOf(';');
        String base = semicolon < 0 ? header : header.substring(0, semicolon);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static @Nullable Charset charsetOf(@Nullable String header) {
        if (StringUtils.isBlank(header)) {
            return null;
        }
        return parseCharsetParameter(header).orElse(null);
    }

    private static Optional<Charset> parseCharsetParameter(String header) {
        for (String part : header.split(";")) {
            String token = part.trim();
            if (!token.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                continue;
            }
            String name = StringUtils.strip(token.substring("charset=".length()).trim(), "\"'");
            try {
                return Optional.of(Charset.forName(name));
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                log.trace("HttpFetcher: unknown charset '{}' in Content-Type", name);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static HttpFetchResult failure(String url, String message) {
        return HttpFetchResult.builder()
                .status(0)
                .body(new byte[0])
                .finalUrl(url)
                .error(message)
                .build();
    }

    /** Drops rate-limiter entries for hosts that have not been seen recently. */
    public int evictStaleHosts() {
        return rateLimiter.evictStale();
    }

    /** Configured request timeout — used by callers that size their own pools. */
    public Duration readTimeout() {
        return config.readTimeout().value();
    }
}
