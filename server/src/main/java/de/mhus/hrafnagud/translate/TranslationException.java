package de.mhus.hrafnagud.translate;

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
 */
@Getter
public class TranslationException extends RuntimeException {

    private final boolean retryable;

    public TranslationException(String message, boolean retryable, @Nullable Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static TranslationException permanent(String message, @Nullable Throwable cause) {
        return new TranslationException(message, false, cause);
    }

    public static TranslationException transient_(String message, @Nullable Throwable cause) {
        return new TranslationException(message, true, cause);
    }
}
