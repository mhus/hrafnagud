package de.mhus.hrafnagud.api.article;

/**
 * State of the full-text fetch for one article.
 *
 * <p>A feed entry almost always carries a teaser only, so the article body
 * has to be fetched from the publisher's page. That fetch is an order of
 * magnitude slower than the feed poll and fails in far more interesting
 * ways, which is why it has its own state machine and its own worker rather
 * than happening inline during ingest.
 *
 * <ul>
 *   <li>{@link #PENDING} — queued. The initial state of every ingested
 *       article, and the state a retryable failure returns to.</li>
 *   <li>{@link #FETCHED} — body stored. Terminal on the happy path.</li>
 *   <li>{@link #PAYWALL} — the page loaded but the body is gated. Terminal:
 *       retrying costs requests and will not produce a different answer.</li>
 *   <li>{@link #BLOCKED} — we are not allowed to fetch it: {@code robots.txt}
 *       disallows the path, or the host answered 403/429 past our retry
 *       budget. Terminal, and deliberately distinct from
 *       {@link #FAILED} — this is a "we must not", not a "we could not".</li>
 *   <li>{@link #FAILED} — retry budget exhausted on transport or extraction
 *       errors. Terminal until an operator requeues it.</li>
 *   <li>{@link #SKIPPED} — not attempted on purpose, e.g. the source is
 *       configured teaser-only or the entry already carried a full body.</li>
 * </ul>
 */
public enum ContentStatus {

    /** Queued for the content worker. */
    PENDING,

    /** Body fetched and stored. */
    FETCHED,

    /** Body exists but is gated behind a paywall. */
    PAYWALL,

    /** Fetching is disallowed (robots.txt, persistent 403/429). */
    BLOCKED,

    /** Retry budget exhausted. */
    FAILED,

    /** Deliberately not attempted. */
    SKIPPED;

    /** {@code true} when no further automatic attempt will be made. */
    public boolean terminal() {
        return this != PENDING;
    }
}
