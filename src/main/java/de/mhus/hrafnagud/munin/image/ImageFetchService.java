package de.mhus.hrafnagud.munin.image;

import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import de.mhus.hrafnagud.settings.Settings;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Fetches one image and hands the bytes to {@link ImageService}.
 *
 * <p>Goes through the shared {@link HttpFetcher} like everything else that
 * leaves this service, so an image request is paced by the same per-host
 * limiter, carries the same user agent and takes the same proxy. That is the
 * point of there being one exit: adding a fourth kind of outbound traffic did
 * not need a fourth piece of politeness.
 *
 * <h2>robots.txt is not consulted</h2>
 * Deliberately, and consistent with the existing split (obeyed for article
 * pages, not for feeds). An image here is a subresource of a page we were
 * already allowed to fetch — the same relationship a browser has to it, and
 * browsers do not consult robots for subresources either. What does apply is
 * the per-host pacing, which is the part a publisher actually notices.
 */
@Service
@Slf4j
public class ImageFetchService {

    /**
     * Media types stored. Anything else is a failure rather than a copy.
     *
     * <p>An {@code <img>} whose URL answers with HTML is the ordinary shape of
     * a consent wall or an error page, and storing that under
     * {@code image/...} would put a login page into the archive as somebody's
     * lead photo. SVG is excluded for a different reason: it is a document
     * that can carry script and remote references, so serving it back from our
     * own origin is not the same act as serving a JPEG.
     */
    private static final Set<String> STORABLE = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif");

    private final HttpFetcher fetcher;
    private final ImageService imageService;
    private final Settings.Image config;

    public ImageFetchService(HttpFetcher fetcher, ImageService imageService, Settings settings) {
        this.fetcher = fetcher;
        this.imageService = imageService;
        this.config = settings.getImage();
    }

    /** Fetches {@code image} and records the outcome either way. */
    public void fetch(ImageDocument image, Instant now) {
        HttpFetchResult response = fetcher.get(image.getUrl());

        String rejection = reject(response, config.maxBytes().value());
        if (rejection != null) {
            imageService.recordFailure(image.getId(), rejection, image.getAttempts(), now);
            log.debug("Image {} not stored: {}", image.getUrl(), rejection);
            return;
        }

        imageService.recordStored(image.getId(), response.getBody(),
                response.getContentType(), now);
        log.debug("Stored image {} — {} KiB, {}", image.getUrl(),
                response.getBody().length / 1024, response.getContentType());
    }

    /**
     * Why this response is not an image worth keeping, or null when it is.
     *
     * <p>Separated and static so every rejection reason is testable without a
     * server: this is the whole of what "a usable image" means, and it is the
     * part that decides what ends up in the archive.
     */
    static @Nullable String reject(HttpFetchResult response, long maxBytes) {
        if (!response.isSuccess()) {
            return StringUtils.defaultIfBlank(response.getError(),
                    "HTTP " + response.getStatus());
        }
        String mime = normalise(response.getContentType());
        if (mime == null) {
            return "no content type declared";
        }
        if (!STORABLE.contains(mime)) {
            return "not a storable image type: " + mime;
        }
        int length = response.getBody().length;
        if (length == 0) {
            return "empty body";
        }
        if (length > maxBytes) {
            // The shared body cap may also have truncated it, which is the
            // same outcome from the other direction: what we hold is not the
            // image, so storing it would be storing a fragment.
            return "larger than the " + maxBytes + " byte limit (" + length + ")";
        }
        return null;
    }

    private static @Nullable String normalise(@Nullable String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return null;
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }
}
