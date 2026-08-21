package de.mhus.hrafnagud.munin.image;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import org.junit.jupiter.api.Test;

/**
 * What counts as an image worth keeping.
 *
 * <p>This is the gate between the open web and the archive's own storage, so
 * every rejection reason is pinned. The failure mode it exists to prevent is
 * not an error but a success: a consent page or an error document stored under
 * {@code image/*} and served back later as somebody's lead photo.
 */
class ImageRejectionTest {

    private static final long MAX = 4L * 1024 * 1024;

    private static HttpFetchResult response(int status, String contentType, int bytes) {
        return HttpFetchResult.builder()
                .status(status)
                .body(new byte[bytes])
                .contentType(contentType)
                .finalUrl("https://img.example.com/a.jpg")
                .build();
    }

    @Test
    void jpeg_isStored() {
        assertThat(ImageFetchService.reject(response(200, "image/jpeg", 94_000), MAX)).isNull();
    }

    @Test
    void theOtherRasterFormats_areStored() {
        for (String mime : new String[] {"image/png", "image/webp", "image/gif", "image/avif"}) {
            assertThat(ImageFetchService.reject(response(200, mime, 1000), MAX))
                    .as(mime)
                    .isNull();
        }
    }

    @Test
    void contentTypeCase_doesNotMatter() {
        assertThat(ImageFetchService.reject(response(200, "IMAGE/JPEG", 1000), MAX)).isNull();
    }

    @Test
    void html_isRejected() {
        // The ordinary shape of a consent wall behind an <img> URL.
        assertThat(ImageFetchService.reject(response(200, "text/html", 8000), MAX))
                .contains("not a storable image type");
    }

    @Test
    void svg_isRejected() {
        // Not a raster image but a document that can carry script and remote
        // references. Serving one back from our own origin is a different act
        // from serving a JPEG.
        assertThat(ImageFetchService.reject(response(200, "image/svg+xml", 2000), MAX))
                .contains("not a storable image type");
    }

    @Test
    void missingContentType_isRejected() {
        assertThat(ImageFetchService.reject(response(200, null, 1000), MAX))
                .isEqualTo("no content type declared");
    }

    @Test
    void notFound_isRejectedWithTheStatus() {
        assertThat(ImageFetchService.reject(response(404, "text/html", 100), MAX))
                .isEqualTo("HTTP 404");
    }

    @Test
    void transportFailure_keepsItsMessage() {
        HttpFetchResult failed = HttpFetchResult.builder()
                .status(0)
                .body(new byte[0])
                .finalUrl("https://img.example.com/a.jpg")
                .error("ConnectException: timed out")
                .build();

        assertThat(ImageFetchService.reject(failed, MAX)).isEqualTo("ConnectException: timed out");
    }

    @Test
    void emptyBody_isRejected() {
        // A 200 with no bytes stores an image file of zero length, which looks
        // like a stored image everywhere except when it is displayed.
        assertThat(ImageFetchService.reject(response(200, "image/jpeg", 0), MAX))
                .isEqualTo("empty body");
    }

    @Test
    void oversized_isRejected() {
        assertThat(ImageFetchService.reject(response(200, "image/jpeg", 5_000_000), MAX))
                .contains("larger than the");
    }

    @Test
    void exactlyAtTheLimit_isStored() {
        assertThat(ImageFetchService.reject(response(200, "image/jpeg", (int) MAX), MAX)).isNull();
    }

    @Test
    void redirectThatCouldNotBeFollowed_carriesItsOwnMessage() {
        // The fetcher already words this one; the image path must not replace
        // it with a bare status.
        HttpFetchResult moved = HttpFetchResult.builder()
                .status(301)
                .body(new byte[0])
                .finalUrl("https://img.example.com/a.jpg")
                .error("HTTP 301 — redirect to http://img.example.com/a.jpg not followed")
                .build();

        assertThat(ImageFetchService.reject(moved, MAX)).contains("not followed");
    }
}
