package de.mhus.hrafnagud.munin.source;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.time.Duration;

/**
 * Decides how long to wait before polling a source again.
 *
 * <p>A fixed interval for every feed is wrong in both directions at once:
 * a wire service publishing forty items an hour loses entries between polls
 * because its feed window rolls over, while a regional weekly gets polled
 * two thousand times for each item it publishes. Both cost something — the
 * first costs coverage, the second costs the publisher's bandwidth and our
 * standing with them.
 *
 * <p>So the interval follows observed behaviour. Delivering a lot shortens
 * it, delivering nothing lengthens it, and failing lengthens it fast. The
 * adjustment is deliberately asymmetric: halving on a busy poll reacts
 * quickly to a feed that is outrunning us, while growth is gradual so a
 * quiet weekend does not push a daily paper out to the maximum interval.
 *
 * <p>Pure and stateless, so the behaviour can be tested without a database
 * or a clock.
 */
public final class FetchSchedulePolicy {

    /** Multiplier applied to the interval after a poll that yielded nothing. */
    private static final double IDLE_GROWTH = 1.5;

    private final MuninProperties.Feed config;

    public FetchSchedulePolicy(MuninProperties.Feed config) {
        this.config = config;
    }

    /**
     * Next interval in seconds.
     *
     * @param currentSeconds interval used for the poll that just finished
     * @param outcome        how that poll went
     * @param newArticles    articles the poll produced that we did not have
     * @param consecutiveFailures failure count <em>after</em> this poll
     */
    public long nextIntervalSeconds(long currentSeconds, FetchOutcome outcome, int newArticles,
            int consecutiveFailures) {

        long current = clampToBounds(currentSeconds <= 0
                ? config.getDefaultInterval().getSeconds()
                : currentSeconds);

        if (!outcome.successful()) {
            return backoffSeconds(current, consecutiveFailures);
        }

        if (newArticles >= config.getBusyThreshold()) {
            // The feed may have rolled over between polls, so entries could
            // have been missed. Close the gap quickly.
            return clampToBounds(Math.max(current / 2, config.getMinInterval().getSeconds()));
        }
        if (newArticles > 0) {
            return current;
        }
        return clampToBounds((long) Math.ceil(current * IDLE_GROWTH));
    }

    /**
     * Geometric backoff on the failure count, capped so that a feed which
     * has been down for a week still gets retried daily rather than
     * effectively never.
     *
     * <p>The source is never disabled by this path. A publisher's outage,
     * a certificate that expired over a holiday, a DNS change — all of them
     * resolve themselves, and a registry that quietly shrinks on every
     * transient problem is one nobody can trust.
     */
    private long backoffSeconds(long currentSeconds, int consecutiveFailures) {
        int exponent = Math.min(Math.max(consecutiveFailures, 1), 16);
        long candidate = currentSeconds;
        for (int i = 0; i < exponent && candidate < config.getMaxFailureInterval().getSeconds(); i++) {
            candidate = Math.min(candidate * 2, config.getMaxFailureInterval().getSeconds());
        }
        return Math.max(candidate, config.getMinInterval().getSeconds());
    }

    /** Interval a newly created source starts with. */
    public long initialIntervalSeconds(Long requested) {
        return requested == null || requested <= 0
                ? config.getDefaultInterval().getSeconds()
                : clampToBounds(requested);
    }

    /** Forces a value into the configured window. */
    public long clampToBounds(long seconds) {
        long min = config.getMinInterval().getSeconds();
        long max = config.getMaxInterval().getSeconds();
        return Math.min(Math.max(seconds, min), max);
    }

    /** Lease length used when claiming a source for polling. */
    public Duration claimLease() {
        return config.getClaimLease();
    }
}
