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

    /** Transport failure message, {@code null} when the request completed. */
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

    /** Body decoded with the header charset, falling back to UTF-8. */
    public String bodyAsText() {
        Charset charset = headerCharset == null ? StandardCharsets.UTF_8 : headerCharset;
        return new String(body, charset);
    }
}
