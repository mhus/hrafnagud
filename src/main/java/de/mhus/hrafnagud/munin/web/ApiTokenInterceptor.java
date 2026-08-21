package de.mhus.hrafnagud.munin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects calls to the operator API that do not carry the configured bearer
 * token.
 *
 * <p>An interceptor rather than a check per controller: four controllers and
 * two dozen methods would be two dozen chances to forget the next one.
 *
 * <p><b>Why not the Ode guard.</b> {@code vance-ode-core} ships an
 * equivalent, and this duplicates about thirty lines of it. Using it here
 * would put {@code de.mhus.vance} on Munin's import list, and Munin not
 * depending on Vancetope is the one hard rule of the architecture — the
 * archive has to be collectable and administrable with no brain anywhere near
 * it. Thirty lines is the price of that, and {@code ModuleBoundaryTest} is
 * what would otherwise have caught the shortcut.
 *
 * <p><b>Empty token means no check</b>, deliberately: that is how every
 * installation of this service has run so far, and a library-style upgrade
 * that starts answering 401 to the operator's own scripts would be a worse
 * surprise than an unguarded API on a loopback binding. Set it before the
 * port is reachable by anyone else.
 */
public class ApiTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenInterceptor.class);

    private static final String BEARER = "Bearer ";

    private final String token;

    public ApiTokenInterceptor(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public boolean isSecured() {
        return !token.isEmpty();
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!isSecured()) {
            return true;
        }
        String presented = bearer(request);
        if (presented != null && matches(presented)) {
            return true;
        }
        // The reason goes to the log and not into the response: the party
        // being refused is the last one that should be told which half of its
        // credential was wrong.
        log.debug("API: rejected {} {} — missing or wrong bearer token",
                request.getMethod(), request.getRequestURI());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    /**
     * The token from an {@code Authorization: Bearer …} header, or null when
     * there is none. A query parameter is deliberately not accepted: it would
     * land in every access log and proxy cache key between here and the
     * browser.
     */
    private static @Nullable String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String presented = header.substring(BEARER.length()).trim();
        return presented.isEmpty() ? null : presented;
    }

    /** Constant-time comparison — a shared secret should not leak by timing. */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
