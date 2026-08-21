package de.mhus.hrafnagud.munin.source;

import de.mhus.hrafnagud.api.source.FetchOutcome;
import de.mhus.hrafnagud.config.MuninProperties;
import de.mhus.hrafnagud.settings.Settings;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>The bounds are read per call rather than captured, because they are
 * settings: widening a ceiling has to reach a running collector. What it
 * reaches is the <em>next</em> scheduling of each source — the poll times
 * already written stay as they are until that source comes round again, which
 * is why a change looks gradual rather than instant.
 */
public final class FetchSchedulePolicy {

    private static final Logger log = LoggerFactory.getLogger(FetchSchedulePolicy.class);

    /** Multiplier applied to the interval after a poll that yielded nothing. */
    private static final double IDLE_GROWTH = 1.5;

    private final Settings.Feed config;

    /**
     * The named interval classes. Structure rather than a knob: adding one is
     * a deployment, so it stays a property while the bounds it falls back to
     * are settings.
     */
    private final Map<String, MuninProperties.Profile> configuredProfiles;

    /** Names already complained about, so an unknown one warns once, not per poll. */
    private final Set<String> unknownReported = ConcurrentHashMap.newKeySet();

    public FetchSchedulePolicy(Settings.Feed config,
            Map<String, MuninProperties.Profile> configuredProfiles) {
        this.config = config;
        this.configuredProfiles = configuredProfiles;
    }

    private static Duration value(@Nullable Duration configured, Duration fallback) {
        return configured == null ? fallback : configured;
    }

    /** The unnamed profile: the bounds as they stand right now. */
    private FetchProfile defaultProfile() {
        return new FetchProfile(FetchProfile.DEFAULT_NAME,
                config.defaultInterval().value(),
                config.minInterval().value(),
                config.maxInterval().value());
    }

    /**
     * The profile of that name, or the default.
     *
     * <p>An unknown name falls back rather than failing. The name arrives from
     * a document somebody typed, sometimes long before the profile is
     * configured; refusing to poll a source over a spelling mistake would turn
     * a configuration slip into a silent gap in the archive. It is logged once
     * per name.
     */
    public FetchProfile profile(@Nullable String name) {
        FetchProfile fallback = defaultProfile();
        if (StringUtils.isBlank(name) || FetchProfile.DEFAULT_NAME.equals(name)) {
            return fallback;
        }
        MuninProperties.Profile configured = configuredProfiles.get(name);
        if (configured != null) {
            return new FetchProfile(name,
                    value(configured.getDefaultInterval(), fallback.defaultInterval()),
                    value(configured.getMinInterval(), fallback.minInterval()),
                    value(configured.getMaxInterval(), fallback.maxInterval()));
        }
        if (unknownReported.add(name)) {
            log.warn("Unknown fetch profile '{}' — falling back to the default ({}–{}). "
                            + "Configure it under munin.feed.profiles.{}",
                    name, fallback.minInterval(), fallback.maxInterval(), name);
        }
        return fallback;
    }

    /** Profile names this build knows, for diagnostics and the API. */
    public Map<String, FetchProfile> profiles() {
        Map<String, FetchProfile> all = new LinkedHashMap<>();
        all.put(FetchProfile.DEFAULT_NAME, defaultProfile());
        configuredProfiles.keySet().forEach(name -> all.put(name, profile(name)));
        return all;
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
        return nextIntervalSeconds(defaultProfile(), currentSeconds, outcome, newArticles,
                consecutiveFailures);
    }

    /** The same, for a source of a named class. */
    public long nextIntervalSeconds(FetchProfile profile, long currentSeconds,
            FetchOutcome outcome, int newArticles, int consecutiveFailures) {

        long current = profile.clamp(currentSeconds <= 0
                ? profile.defaultIntervalSeconds()
                : currentSeconds);

        if (!outcome.successful()) {
            return backoffSeconds(profile, current, consecutiveFailures);
        }

        if (newArticles >= config.busyThreshold().value()) {
            // The feed may have rolled over between polls, so entries could
            // have been missed. Close the gap quickly.
            return profile.clamp(Math.max(current / 2, profile.minIntervalSeconds()));
        }
        if (newArticles > 0) {
            return current;
        }
        return profile.clamp((long) Math.ceil(current * IDLE_GROWTH));
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
    private long backoffSeconds(FetchProfile profile, long currentSeconds,
            int consecutiveFailures) {

        // Never more often than a healthy source of the same class: with a
        // weekly profile, a 24-hour failure cap would poll a broken blog seven
        // times as often as a working one, which is the wrong way round.
        long cap = Math.max(config.maxFailureInterval().value().getSeconds(),
                profile.maxIntervalSeconds());
        int exponent = Math.min(Math.max(consecutiveFailures, 1), 16);
        long candidate = currentSeconds;
        for (int i = 0; i < exponent && candidate < cap; i++) {
            candidate = Math.min(candidate * 2, cap);
        }
        return Math.max(candidate, profile.minIntervalSeconds());
    }

    /** Interval a newly created source starts with. */
    public long initialIntervalSeconds(@Nullable Long requested) {
        return initialIntervalSeconds(defaultProfile(), requested);
    }

    /** The same, for a source of a named class. */
    public long initialIntervalSeconds(FetchProfile profile, @Nullable Long requested) {
        return requested == null || requested <= 0
                ? profile.defaultIntervalSeconds()
                : profile.clamp(requested);
    }

    /** Forces a value into the default profile's window. */
    public long clampToBounds(long seconds) {
        return defaultProfile().clamp(seconds);
    }

    /** Lease length used when claiming a source for polling. */
    public Duration claimLease() {
        return config.claimLease().value();
    }
}
