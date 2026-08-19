package de.mhus.hrafnagud.centauri;

import de.mhus.hrafnagud.munin.article.ArticleCursor;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * Wire form of {@link ArticleCursor} — {@code <iso-instant>|<articleId>}.
 *
 * <p>Opaque to Vancetope by contract, which is why it can stay readable
 * rather than being base64'd into something nobody can debug. Opaque means
 * "the reader does not interpret it", not "the reader must not be able to".
 *
 * <p>An unparsable cursor is not an error. A reader that kept one across a
 * change of format would otherwise be stuck on a stream it can never open
 * again; starting from the top is a visible glitch, an endless 400 is not.
 */
final class FeedCursor {

    private static final char SEPARATOR = '|';

    private FeedCursor() {}

    static String encode(Instant publishedAt, String articleId) {
        return publishedAt.toString() + SEPARATOR + articleId;
    }

    /** {@code null} for absent, malformed or empty input — all mean "start at the beginning". */
    static @Nullable ArticleCursor decode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int split = raw.indexOf(SEPARATOR);
        if (split <= 0 || split == raw.length() - 1) {
            return null;
        }
        try {
            return new ArticleCursor(
                    Instant.parse(raw.substring(0, split)),
                    raw.substring(split + 1));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }
}
