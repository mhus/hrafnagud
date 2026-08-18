package de.mhus.hrafnagud.api.source;

/**
 * Result of one poll of a source. Drives the adaptive poll interval and is
 * recorded on the source for operator diagnosis.
 *
 * <ul>
 *   <li>{@link #OK} — feed was fetched and parsed. May still have yielded
 *       zero new articles; "nothing new" is a normal outcome, not a
 *       failure.</li>
 *   <li>{@link #NOT_MODIFIED} — the server answered 304 to our conditional
 *       request. Cheapest possible poll; counts as "nothing new" for
 *       interval purposes.</li>
 *   <li>{@link #FETCH_ERROR} — transport level: DNS, TLS, timeout, 4xx,
 *       5xx. Retried with backoff.</li>
 *   <li>{@link #PARSE_ERROR} — bytes arrived but were not a feed we could
 *       read. Distinguished from {@link #FETCH_ERROR} because it usually
 *       means the URL is wrong (an HTML page, a login wall), which backoff
 *       will never fix — an operator has to look.</li>
 * </ul>
 */
public enum FetchOutcome {

    /** Fetched and parsed successfully. */
    OK,

    /** Server replied 304 Not Modified. */
    NOT_MODIFIED,

    /** Transport-level failure. */
    FETCH_ERROR,

    /** Response body was not a parseable feed. */
    PARSE_ERROR;

    /** {@code true} for the two outcomes that mean "the poll worked". */
    public boolean successful() {
        return this == OK || this == NOT_MODIFIED;
    }
}
