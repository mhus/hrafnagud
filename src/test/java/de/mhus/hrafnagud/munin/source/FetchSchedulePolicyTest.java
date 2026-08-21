package de.mhus.hrafnagud.munin.source;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.TestSettings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FetchSchedulePolicyTest {

    private static final long MIN = Duration.ofMinutes(5).getSeconds();
    private static final long MAX = Duration.ofHours(12).getSeconds();
    private static final long DEFAULT = Duration.ofMinutes(30).getSeconds();

    private FetchSchedulePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new FetchSchedulePolicy(TestSettings.defaults().getFeed(), Map.of());
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

    // ── profiles ────────────────────────────────────────────────────────
    //
    // The reason profiles exist: the adaptive policy only moves within the
    // bounds it is given. Everything below is about a class of source whose
    // bounds are not the news ones.

    @Test
    void a_profile_widens_the_ceiling_the_default_would_have_imposed() {
        FetchSchedulePolicy withBlogs = policyWithBlogProfile();
        FetchProfile blog = withBlogs.profile("blog");

        // Idle growth from a daily start runs to a week, not to twelve hours.
        long next = withBlogs.nextIntervalSeconds(blog, Duration.ofDays(1).getSeconds(),
                FetchOutcome.OK, 0, 0);

        assertThat(next).isEqualTo(Duration.ofDays(1).getSeconds() * 3 / 2);
        assertThat(blog.maxIntervalSeconds()).isEqualTo(Duration.ofDays(7).getSeconds());
    }

    /**
     * The bug profiles were built for: a daily interval was silently clamped
     * to the global twelve-hour ceiling, so "poll this once a day" could not
     * be expressed at all.
     */
    @Test
    void a_daily_interval_survives_under_a_blog_profile_and_would_not_under_the_default() {
        FetchSchedulePolicy withBlogs = policyWithBlogProfile();
        long daily = Duration.ofDays(1).getSeconds();

        assertThat(withBlogs.profile("blog").clamp(daily)).isEqualTo(daily);
        assertThat(withBlogs.clampToBounds(daily)).isEqualTo(MAX);
    }

    @Test
    void an_unknown_profile_falls_back_to_the_default_rather_than_failing() {
        // A name can be configured after the catalogue that uses it; refusing
        // to poll over a spelling mistake would be a gap in the archive.
        FetchProfile profile = policy.profile("does-not-exist");

        assertThat(profile.name()).isEqualTo(FetchProfile.DEFAULT_NAME);
        assertThat(profile.maxIntervalSeconds()).isEqualTo(MAX);
    }

    @Test
    void no_profile_and_the_default_profile_are_the_same_thing() {
        assertThat(policy.profile(null)).isEqualTo(policy.profile("default"));
        assertThat(policy.profile("  ")).isEqualTo(policy.profile(null));
    }

    /** A broken source must never be polled more often than a working one. */
    @Test
    void failure_backoff_never_goes_below_the_profiles_own_ceiling() {
        FetchSchedulePolicy withBlogs = policyWithBlogProfile();
        FetchProfile blog = withBlogs.profile("blog");

        long next = withBlogs.nextIntervalSeconds(blog, Duration.ofDays(7).getSeconds(),
                FetchOutcome.FETCH_ERROR, 0, 5);

        // The global failure cap is 24 h; the weekly ceiling wins.
        assertThat(next).isGreaterThanOrEqualTo(Duration.ofDays(7).getSeconds());
    }

    @Test
    void a_profile_inherits_what_it_does_not_set() {
        MuninProperties properties = new MuninProperties();
        MuninProperties.Profile sparse = new MuninProperties.Profile();
        sparse.setMaxInterval(Duration.ofDays(7));
        properties.getFeed().getProfiles().put("slow", sparse);

        FetchProfile profile = new FetchSchedulePolicy(
                TestSettings.of(properties).getFeed(), properties.getFeed().getProfiles())
                .profile("slow");

        assertThat(profile.maxIntervalSeconds()).isEqualTo(Duration.ofDays(7).getSeconds());
        assertThat(profile.minIntervalSeconds()).isEqualTo(MIN);
        assertThat(profile.defaultIntervalSeconds()).isEqualTo(DEFAULT);
    }

    private static FetchSchedulePolicy policyWithBlogProfile() {
        MuninProperties properties = new MuninProperties();
        MuninProperties.Profile blog = new MuninProperties.Profile();
        blog.setDefaultInterval(Duration.ofDays(1));
        blog.setMinInterval(Duration.ofHours(6));
        blog.setMaxInterval(Duration.ofDays(7));
        properties.getFeed().getProfiles().put("blog", blog);
        return new FetchSchedulePolicy(
                TestSettings.of(properties).getFeed(), properties.getFeed().getProfiles());
    }
}
