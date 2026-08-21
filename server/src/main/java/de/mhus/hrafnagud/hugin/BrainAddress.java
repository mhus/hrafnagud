package de.mhus.hrafnagud.hugin;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Whether a brain has an address at all. A blank one does not count.
 *
 * <p>This exists because of a gap between two reasonable decisions. Ode's
 * auto-configuration is conditional on the <em>presence</em> of
 * {@code vance.ode.base-url}, and hrafnagud's {@code application.yml} maps the
 * operator's environment onto it as {@code ${VANCE_BRAIN_URL:}} so that the
 * knob is documented where the others are. With the variable unset that
 * property is present and empty — which Spring counts as configured. The result
 * was a transport and an event client built around an empty URL, a provider
 * wired to them, and a startup line reading
 * {@code Translating into 'de' via vance-ode} on an installation with no brain
 * anywhere. The queue then drained into failures instead of standing still and
 * saying so, which is the opposite of what the startup report is for.
 *
 * <p>So the packages that call out ask this before they wire anything. Deciding
 * it here rather than in each of them keeps one answer to "is there a brain",
 * and keeps the explanation in one place instead of two.
 *
 * <p>Not fixed in the library, deliberately: {@code vance-ode} is published to
 * Maven Central and a released version is final, so a condition that treats
 * blank as absent belongs in its next release rather than in a hurry. Until
 * then this is the honest reading on the consumer's side — and it also covers
 * the case the library never could, an operator who sets the variable to a
 * space.
 */
public final class BrainAddress {

    private BrainAddress() {
    }

    /** {@code true} when {@code vance.ode.base-url} names something. */
    public static boolean isConfigured(@Nullable String baseUrl) {
        return StringUtils.isNotBlank(baseUrl);
    }
}
