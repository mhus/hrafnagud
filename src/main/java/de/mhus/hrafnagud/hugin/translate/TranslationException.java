package de.mhus.hrafnagud.hugin.translate;

import java.time.Duration;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * A translation that could not be produced.
 *
 * <p>Carries whether retrying is worth it, because that is the only thing
 * the queue does with the answer. A provider knows — a rejected token will
 * be rejected again, a timeout may not be — and pushing that judgement to
 * the caller would mean re-deriving it from an exception type it does not
 * own.
 *
 * <h2>Three outcomes, not two</h2>
 * <b>Throttled</b> is a retryable failure that must not count as an attempt.
 * On a free tier a rate limit is not an accident, it is the normal state — and
 * with three attempts and a doubling delay, treating it as a failure would mark
 * every article {@code FAILED} after three of them. Nothing was wrong with the
 * article, nothing was even asked of the model; the only correct response is to
 * come back later with the budget untouched.
 */
@Getter
public class TranslationException extends RuntimeException {

    private final boolean retryable;

    /**
     * {@code true} when the provider was rate-limited. Implies
     * {@link #retryable}, and additionally means the attempt did not happen:
     * the queue gives the budget back and waits.
     */
    private final boolean throttled;

    /** How long the provider asked to be left alone, when it said. */
    private final @Nullable Duration retryAfter;

    public TranslationException(String message, boolean retryable, @Nullable Throwable cause) {
        this(message, retryable, false, null, cause);
    }

    private TranslationException(String message, boolean retryable, boolean throttled,
            @Nullable Duration retryAfter, @Nullable Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.throttled = throttled;
        this.retryAfter = retryAfter;
    }

    public static TranslationException permanent(String message, @Nullable Throwable cause) {
        return new TranslationException(message, false, cause);
    }

    public static TranslationException transient_(String message, @Nullable Throwable cause) {
        return new TranslationException(message, true, cause);
    }

    /**
     * Rate-limited: come back later, and do not charge the article for it.
     *
     * @param retryAfter what the provider asked for, or {@code null} when it
     *                   did not say — most clients do not surface the header,
     *                   and inventing a number would be worse than using the
     *                   configured cooldown
     */
    public static TranslationException throttled(String message,
            @Nullable Duration retryAfter, @Nullable Throwable cause) {
        return new TranslationException(message, true, true, retryAfter, cause);
    }
}
