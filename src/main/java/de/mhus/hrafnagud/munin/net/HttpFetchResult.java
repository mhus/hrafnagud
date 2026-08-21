package de.mhus.hrafnagud.munin.net;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of one HTTP GET.
 *
 * <p>Carries bytes rather than a decoded string: a feed's real encoding is
 * declared inside the XML as often as in the {@code Content-Type} header,
 * and the two disagree regularly. Only the parser knows which to believe,
 * so decoding is left to it.
 */
@Value
@Builder
public class HttpFetchResult {

    /** HTTP status, or {@code 0} when the request never completed. */
    int status;

    /** Response body, empty on 304 and on failure. */
    byte[] body;

    /** Charset from the {@code Content-Type} header, when it declared one. */
    @Nullable Charset headerCharset;

    /** {@code Content-Type} without parameters, lowercased. */
    @Nullable String contentType;

    /** Validators to send back on the next conditional request. */
    @Nullable String etag;

    @Nullable String lastModified;

    /** URL after redirects — the one worth storing as the article's location. */
    String finalUrl;

    /**
     * Where this feed now lives, when a <em>permanent</em> redirect moved it
     * and the new location answered.
     *
     * <p>Set only for 301 and 308. A 302, 303 or 307 says "not here right
     * now", which is the publisher asking us not to remember it, and a
     * caller that stored it anyway would pin a feed to whatever temporary
     * host answered once.
     *
     * <p>Null on every ordinary fetch, including one that followed
     * redirects: the client resolves those on its own and the caller has no
     * decision to make. This field exists for the one case it cannot —
     * see {@link HttpFetcher#repairTarget}.
     */
    @Nullable String movedTo;

    /**
     * Failure message, {@code null} when the response is one the caller can
     * work with.
     *
     * <p>Usually a transport failure. Also set for a redirect that could not
     * be followed, because "HTTP 301" alone sends an operator looking for a
     * problem at the publisher when the refusal is ours.
     */
    @Nullable String error;

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isNotModified() {
        return status == 304;
    }

    /** {@code true} for the statuses that mean "stop asking for a while". */
    public boolean isThrottledOrForbidden() {
        return status == 403 || status == 429;
    }

    /**
     * The {@code Content-Type} header as it arrived, mime type and charset
     * together.
     *
     * <p>The two are stored apart because most callers want one or the other.
     * An XML parser wants both, and giving it only the mime type is not a
     * smaller truth but a different one: RFC 3023 reads {@code text/xml}
     * without a charset parameter as <b>US-ASCII</b>, so a stripped header
     * turns a correctly declared UTF-8 feed into one replacement character per
     * byte. Reassembled here rather than at the call site, so the next parser
     * does not have to know that.
     */
    public @Nullable String contentTypeHeader() {
        if (contentType == null) {
            return null;
        }
        return headerCharset == null
                ? contentType
                : contentType + "; charset=" + headerCharset.name();
    }

    /** Body decoded with the header charset, falling back to UTF-8. */
    public String bodyAsText() {
        Charset charset = headerCharset == null ? StandardCharsets.UTF_8 : headerCharset;
        return new String(body, charset);
    }
}
