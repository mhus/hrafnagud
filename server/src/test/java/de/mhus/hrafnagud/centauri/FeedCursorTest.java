package de.mhus.hrafnagud.centauri;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.munin.article.ArticleCursor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The cursor's wire form. Round-tripping matters, but the reason this has
 * its own test is the other half: what happens to input that is not a
 * cursor. A reader keeps cursors across restarts and across our releases,
 * so a malformed one is a thing that will happen.
 */
class FeedCursorTest {

    private static final Instant T = Instant.parse("2026-08-19T09:00:00Z");

    @Test
    void a_cursor_survives_the_round_trip() {
        ArticleCursor decoded = FeedCursor.decode(FeedCursor.encode(T, "abc123"));

        assertThat(decoded).isNotNull();
        assertThat(decoded.publishedAt()).isEqualTo(T);
        assertThat(decoded.articleId()).isEqualTo("abc123");
    }

    @Test
    void an_id_containing_the_separator_still_decodes_whole() {
        // Split on the first separator, not the last: the timestamp cannot
        // contain one, an id conceivably could.
        ArticleCursor decoded = FeedCursor.decode(T + "|a|b");

        assertThat(decoded).isNotNull();
        assertThat(decoded.articleId()).isEqualTo("a|b");
    }

    @Test
    void nonsense_means_start_at_the_beginning_rather_than_fail() {
        // A reader that kept a cursor across a format change would otherwise
        // be stuck on a stream it can never open again. One glitchy page
        // beats a permanent error.
        assertThat(FeedCursor.decode("not-a-cursor")).isNull();
        assertThat(FeedCursor.decode("2026-13-45T99:99:99Z|a1")).isNull();
        assertThat(FeedCursor.decode("|a1")).isNull();
        assertThat(FeedCursor.decode(T + "|")).isNull();
        assertThat(FeedCursor.decode("")).isNull();
        assertThat(FeedCursor.decode(null)).isNull();
    }
}
