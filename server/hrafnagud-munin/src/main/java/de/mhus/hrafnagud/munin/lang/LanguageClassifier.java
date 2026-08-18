package de.mhus.hrafnagud.munin.lang;

import org.jspecify.annotations.Nullable;

/**
 * Statistical language classification of a piece of text.
 *
 * <p>An interface rather than a direct call into the library because the
 * classifier is the one component here with a real memory and accuracy
 * trade-off, and swapping it out later must not touch the ingest path.
 */
public interface LanguageClassifier {

    /**
     * Best guess for {@code text} as a lowercase BCP-47 primary subtag, or
     * {@code null} when the classifier will not commit.
     *
     * <p>Returning {@code null} is a normal result, not an error path. A
     * confident wrong language is worse for every downstream consumer than
     * an admitted unknown, so implementations should abstain rather than
     * guess on thin input.
     */
    @Nullable String detect(String text);
}
