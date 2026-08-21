package de.mhus.hrafnagud.munin.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The address of an image, derived from its URL.
 *
 * <p>Pure and deterministic on purpose. Anything holding an image URL — the
 * fetcher, a serving path deciding between a local copy and the original
 * link, a future mount resolving a path — arrives at the same id without
 * asking the database, which is what turns "do we have this image?" into a
 * primary-key read.
 *
 * <p>Hashed rather than used verbatim because an image URL is long, carries
 * query strings and, being publisher-controlled, contains whatever it likes;
 * a fixed-length hex key is a safe path segment and a safe {@code _id}.
 */
public final class ImageKey {

    private ImageKey() {
    }

    /**
     * {@code sha256(url)} in hex, lowercase.
     *
     * <p>The URL is hashed exactly as extraction stored it. Normalising here
     * would be a second, invisible normalisation rule that the stored URL does
     * not follow, and the two drifting apart would mean an image the archive
     * holds under an id nothing computes.
     */
    public static String of(String url) {
        return HexFormat.of().formatHex(sha256(url.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code sha256} of arbitrary bytes, hex — used for the content hash. */
    public static String ofBytes(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java implementation. If it is
            // missing the environment is broken in a way no fallback helps.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
