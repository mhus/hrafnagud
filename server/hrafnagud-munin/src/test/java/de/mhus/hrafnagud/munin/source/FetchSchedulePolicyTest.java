package de.mhus.hrafnagud.munin.source;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.munin.config.MuninProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FetchSchedulePolicyTest {

    private static final long MIN = Duration.ofMinutes(5).getSeconds();
    private static final long MAX = Duration.ofHours(12).getSeconds();
    private static final long DEFAULT = Duration.ofMinutes(30).getSeconds();

    private FetchSchedulePolicy policy;

    @BeforeEach
    void setUp() {
        MuninProperties.Feed config = new MuninProperties().getFeed();
        policy = new FetchSchedulePolicy(config);
    }

    @Test
    void busyFeed_halvesTheInterval() {
        // Five or more new items suggests the feed's window may have rolled
        // over between polls, so entries could have been missed.
        long next = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.OK, 5, 0);

        assertThat(next).isEqualTo(DEFAULT / 2);
    }

    @Test
    void modestlyActiveFeed_keepsTheInterval() {
        long next = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.OK, 2, 0);

        assertThat(next).isEqualTo(DEFAULT);
    }

    @Test
    void quietFeed_growsTheIntervalGradually() {
        // Growth is gentler than the halving on the busy side, so a quiet
        // weekend does not push a daily paper out to the maximum.
        long next = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.OK, 0, 0);

        assertThat(next).isGreaterThan(DEFAULT).isLessThan(DEFAULT * 2);
    }

    @Test
    void notModified_countsAsQuiet() {
        long fromOk = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.OK, 0, 0);
        long fromNotModified =
                policy.nextIntervalSeconds(DEFAULT, FetchOutcome.NOT_MODIFIED, 0, 0);

        assertThat(fromNotModified).isEqualTo(fromOk);
    }

    @Test
    void growthStopsAtTheMaximum() {
        long next = policy.nextIntervalSeconds(MAX, FetchOutcome.OK, 0, 0);

        assertThat(next).isEqualTo(MAX);
    }

    @Test
    void halvingStopsAtTheMinimum() {
        long next = policy.nextIntervalSeconds(MIN, FetchOutcome.OK, 100, 0);

        assertThat(next).isEqualTo(MIN);
    }

    @Test
    void failuresBackOffGeometrically() {
        long first = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.FETCH_ERROR, 0, 1);
        long third = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.FETCH_ERROR, 0, 3);

        assertThat(first).isGreaterThan(DEFAULT);
        assertThat(third).isGreaterThan(first);
    }

    @Test
    void backoffIsCapped_soADeadFeedIsStillRetriedDaily() {
        // Never disabled and never abandoned: outages end, and a registry
        // that quietly shrinks on transient problems cannot be trusted.
        long next = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.FETCH_ERROR, 0, 50);

        assertThat(next).isEqualTo(Duration.ofHours(24).getSeconds());
    }

    @Test
    void parseErrorBacksOffLikeAFetchError() {
        long next = policy.nextIntervalSeconds(DEFAULT, FetchOutcome.PARSE_ERROR, 0, 1);

        assertThat(next).isGreaterThan(DEFAULT);
    }

    @Test
    void recoveryFromBackoff_isPulledBackUnderTheMaximum() {
        long backedOff = Duration.ofHours(24).getSeconds();

        long next = policy.nextIntervalSeconds(backedOff, FetchOutcome.OK, 1, 0);

        assertThat(next).isEqualTo(MAX);
    }

    @Test
    void zeroOrNegativeCurrentInterval_fallsBackToTheDefault() {
        assertThat(policy.nextIntervalSeconds(0, FetchOutcome.OK, 1, 0)).isEqualTo(DEFAULT);
    }

    @Test
    void initialInterval_usesTheDefaultWhenUnspecified() {
        assertThat(policy.initialIntervalSeconds(null)).isEqualTo(DEFAULT);
        assertThat(policy.initialIntervalSeconds(0L)).isEqualTo(DEFAULT);
    }

    @Test
    void initialInterval_isClampedIntoTheConfiguredWindow() {
        assertThat(policy.initialIntervalSeconds(1L)).isEqualTo(MIN);
        assertThat(policy.initialIntervalSeconds(Duration.ofDays(7).getSeconds())).isEqualTo(MAX);
    }
}
