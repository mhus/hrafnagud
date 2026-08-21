package de.mhus.hrafnagud.api.setting;

/**
 * How a stored setting value is parsed.
 *
 * <p>Values live in the database as text, because that is what an editor and
 * an HTTP body carry. The type is what turns that text back into something the
 * code can use — and, before that, what lets a write be refused instead of
 * stored: {@code "PT5X"} in a duration field is rejected at the API rather than
 * discovered by a worker at three in the morning.
 *
 * <p>There is deliberately no protected or encrypted type here. Hrafnagud's
 * secrets — the operator token and the two Ode keys — stay in the environment
 * where the deployment already keeps them; moving them into the archive's own
 * database would mean a second key to manage and a secret in a place nothing
 * else guards. See {@code specs/settings.md} §5 for that boundary.
 */
public enum SettingType {

    STRING,

    INT,

    LONG,

    DOUBLE,

    BOOLEAN,

    /** ISO-8601 period, e.g. {@code PT30S} or {@code PT24H}. */
    DURATION,

    /**
     * Comma-separated values, e.g. {@code en,de}. Order is kept, blanks are
     * ignored, and what the API renders is what it accepts back.
     */
    STRING_LIST
}
