package de.mhus.hrafnagud.munin.image;

/**
 * Where one image stands in the copy queue.
 *
 * <p>Deliberately without a "not wanted" state. An image the current settings
 * exclude — an inline image while {@code leadOnly} is on — is not queued at
 * all, so turning the filter off later picks it up on the next article rather
 * than leaving a stratum of records that say "skipped under rules that no
 * longer apply".
 */
public enum ImageStatus {

    /** Queued, or waiting for its next attempt. */
    PENDING,

    /** Bytes are in the archive. */
    STORED,

    /**
     * Given up on after {@code maxAttempts}.
     *
     * <p>Not a dead end: the article still references the original URL, so a
     * failed copy costs the independence, not the image.
     */
    FAILED;

    /** {@code true} when the archive holds the bytes. */
    public boolean stored() {
        return this == STORED;
    }
}
