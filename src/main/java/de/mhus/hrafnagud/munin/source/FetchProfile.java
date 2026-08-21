package de.mhus.hrafnagud.munin.source;

import java.time.Duration;

/**
 * How often a <em>class</em> of source is worth polling.
 *
 * <p>One interval policy for everything is wrong as soon as the registry holds
 * more than one kind of publisher. A wire service and a personal blog differ by
 * three orders of magnitude in how often they publish, and the adaptive policy
 * cannot bridge that on its own: it only moves within the bounds it is given,
 * so a blog that posts monthly still gets polled at the ceiling — sixty times
 * per article at a twelve-hour maximum.
 *
 * <p>A named profile rather than three loose numbers on every layer. The name
 * travels catalogue → list → source, so "these are blogs" is said once, where
 * the collection is registered, instead of being re-derived at each layer. It
 * is also the vocabulary an operator already thinks in.
 *
 * <p>Effective values only — resolution against the configured defaults has
 * already happened, so nothing here is null and no caller has to know what a
 * missing value would have meant.
 *
 * @param name            profile name, for logs and diagnostics.
 * @param defaultInterval interval a new source of this class starts with.
 * @param minInterval     floor for the adaptive policy; a busy feed never goes
 *                        below it.
 * @param maxInterval     ceiling for the adaptive policy. This is the value
 *                        that makes a slow class cheap, and the one a global
 *                        setting cannot express.
 */
public record FetchProfile(
        String name,
        Duration defaultInterval,
        Duration minInterval,
        Duration maxInterval) {

    /** Name of the profile a source falls back to when it names none. */
    public static final String DEFAULT_NAME = "default";

    public FetchProfile {
        if (minInterval.compareTo(maxInterval) > 0) {
            throw new IllegalArgumentException(
                    "profile '" + name + "': minInterval " + minInterval
                            + " is above maxInterval " + maxInterval);
        }
    }

    public long defaultIntervalSeconds() {
        return defaultInterval.getSeconds();
    }

    public long minIntervalSeconds() {
        return minInterval.getSeconds();
    }

    public long maxIntervalSeconds() {
        return maxInterval.getSeconds();
    }

    /** Forces a value into this profile's window. */
    public long clamp(long seconds) {
        return Math.min(Math.max(seconds, minIntervalSeconds()), maxIntervalSeconds());
    }
}
